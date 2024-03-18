"use strict";

const RPCClient = require("@alicloud/pop-core").RPCClient;
const _ = require("lodash");
const httpModule = require('https');

// 配置审计地域
// 根据账号归属站点的不同，选择不同地域：
// 1. 中国站：cn-shanghai
// 2. International：ap-southeast-1
const CONFIG_SERVICE_REGION = 'cn-shanghai';

//合规
const COMPLIANCE_TYPE_COMPLIANT = "COMPLIANT";
//不合规
const COMPLIANCE_TYPE_NON_COMPLIANT = "NON_COMPLIANT";
//不适用
const COMPLIANCE_TYPE_NOT_APPLICABLE = "NOT_APPLICABLE";

const keepAliveAgent = new httpModule.Agent({
  keepAlive: false,
});
const requestOption = {
  method: 'POST',
  formatParams: false,
  timeout: 10000,
  agent: keepAliveAgent,
};


exports.handler = (event, context, callback) => {
  const params = JSON.parse(event.toString());
  console.log(params);
  console.log(context);
  main(params, context)
    .then(() => {
      callback(null);
    })
    .catch((err) => callback(err));
};


async function main(eventParams, context) {
  const { logger } = context;
  const {
    invokingEvent: {
      configurationItem
    },
    ruleParameters: {
      tagScopes,
      timezone
    }
  } = eventParams;

  if (!configurationItem) {
    logger.error(`There is no configurationItem in invokingEvent. Params is ${JSON.stringify(eventParams)}`);
    return;
  }

  const { regionId, resourceId, tags, accountId } = configurationItem;
  logger.info(`Start evaluating for resource ${resourceId} of account ${accountId} in region ${regionId}`);

  //校验资源标签是否在要检测的范围内
  if (tagScopes) {
    const allowedTags = JSON.parse(tagScopes);
    if (!tags) {
      logger.info(`Resource ${resourceId} don't need to evaluate`);
      return;
    }
    const resourceTags = JSON.parse(tags);

    var needEvaluate = false;

    for (let i = 0; i < allowedTags.length; i++) {
      if (resourceTags[allowedTags[i].TagKey] != null && resourceTags[allowedTags[i].TagKey].indexOf(allowedTags[i].TagValue) > -1) {
        needEvaluate = true;
        break;
      }
    }

    //忽略资源不在需要巡检的范围内的资源
    if (needEvaluate === false) {
      logger.info(`Resource ${resourceId} don't need to evaluate`);
      return;
    }
  }

  //构造ECS云助手Client
  const client = await getEcsClient(eventParams, context);

  //构造请求参数
  const params = {
    RegionId: regionId,
    Type: "RunShellScript",
    CommandContent: btoa("timedatectl | grep Time | awk -F ':' '{print $2}'"),
    RepeatMode: "Once",
    ContentEncoding: "Base64",
    InstanceId: [resourceId],
    Timeout: 60,
  };

  //请求云助手
  const result = await client.request("RunCommand", params, requestOption);
  const { CommandId: commandId, InvokeId: invokeId } = result;

  while (true) {
    let invocationResult = await getCommandResult(
      commandId,
      invokeId,
      eventParams,
      context,
      client
    );
    if (invocationResult !== null) {
      invocationResult = invocationResult.trim();
      const isCompliant =
        invocationResult === `${timezone.trim()}`;
      const annotation = isCompliant ? {} : {
        desiredValue: timezone.trim(),
        configuration: invocationResult,
      };
      const complianceType = isCompliant ? COMPLIANCE_TYPE_COMPLIANT : COMPLIANCE_TYPE_NON_COMPLIANT;
      await putEvaluationResult(complianceType, eventParams, context, annotation);
      break;
    }
    await sleep(2000);
  }
}

async function getCommandResult(commandId, invokeId, eventParams, context, client) {
  const { regionId, resourceId } = eventParams.invokingEvent.configurationItem;
  const { logger } = context;
  
  const params = {
    RegionId: regionId,
    InvokeId: invokeId,
    InstanceId: resourceId,
    CommandId: commandId,
  };

  const result = await client.request(
    "DescribeInvocationResults",
    params,
    requestOption
  );
  let invocationResult =
    result.Invocation.InvocationResults.InvocationResult[0];

  if (
    !_.isUndefined(invocationResult.InvocationStatus) &&
    _.isEqual(invocationResult.InvocationStatus, "Aborted")
  ) {
    logger.error(`执行失败 错误信息 ${invocationResult.ErrorInfo}`);
    return "";
  } else if (_.isNil(invocationResult.ExitCode)) {
    logger.log("脚本执行中，请等待.......");
    return null;
  } else {
    if (_.isEqual(`${invocationResult.ExitCode}`, "0")) {
      logger.log(`命令输出结果 ` + Buffer.from(invocationResult.Output, 'base64'));
    } else {
      logger.error(
        `错误码 ${invocationResult.ErrorCode} 错误信息 ${invocationResult.ErrorInfo}`
      );
    }

    const buff = Buffer.from(invocationResult.Output, 'base64');
    return buff.toString('ascii');
  }
}

async function getEcsClient(eventParams, context) {
  const { regionId, accountId } = eventParams.invokingEvent.configurationItem;

  //Assume Role到应用账号
  const stsClient = new RPCClient({
    accessKeyId: context.credentials.accessKeyId,
    accessKeySecret: context.credentials.accessKeySecret,
    securityToken: context.credentials.securityToken,
    endpoint: `https://sts.${regionId}.aliyuncs.com`,
    apiVersion: "2015-04-01",
  });

  const params = {
    RegionId: regionId,
    RoleArn: `acs:ram::${accountId}:role/${eventParams.ruleParameters.configFcExecutionRoleName}`,
    RoleSessionName: "EcsTimezoneInspection",
  };

  const applicationAccountCredentials = await stsClient.request(
    "AssumeRole",
    params,
    requestOption
  );

  // 构造 ecs 服务的 client
  const ecsClient = new RPCClient({
    accessKeyId: applicationAccountCredentials.Credentials.AccessKeyId,
    accessKeySecret: applicationAccountCredentials.Credentials.AccessKeySecret,
    securityToken: applicationAccountCredentials.Credentials.SecurityToken,
    endpoint: `https://ecs.${regionId}.aliyuncs.com`,
    apiVersion: "2014-05-26",
  });

  return ecsClient;
}

async function putEvaluationResult(complianceType, eventParams, context, annotation) {
  const {
    invokingEvent: {
      accountId,
      configurationItem: { regionId, resourceId, resourceType },
    },
    resultToken,
    orderingTimestamp,
  } = eventParams;
  const client = new RPCClient({
    accessKeyId: context.credentials.accessKeyId,
    accessKeySecret: context.credentials.accessKeySecret,
    securityToken: context.credentials.securityToken,
    endpoint: `https://config.${CONFIG_SERVICE_REGION}.aliyuncs.com`,
    apiVersion: "2019-01-08",
  });
  const params = {
    ResultToken: resultToken,
    Evaluations: JSON.stringify([
      {
        accountId,
        annotation: JSON.stringify(annotation),
        complianceResourceId: resourceId,
        complianceResourceType: resourceType,
        complianceRegionId: regionId,
        complianceType,
        orderingTimestamp,
      },
    ]),
    //启用删除模式
    DeleteMode: true
  };

  return client.request("PutEvaluations", params, requestOption);
}

function sleep(ms) {
  return new Promise((resolve, reject) => {
    setTimeout(resolve, ms);
  });
}
