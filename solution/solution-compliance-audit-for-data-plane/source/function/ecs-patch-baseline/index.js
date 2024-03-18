'use strict';

const RPCClient = require('@alicloud/pop-core').RPCClient;
const _ = require("lodash");
const httpModule = require('https');

// 配置审计地域
// 根据账号归属站点的不同，选择不同地域：
// 1. 中国站：cn-shanghai
// 2. International：ap-southeast-1
const CONFIG_SERVICE_REGION = 'cn-shanghai';

//合规
const COMPLIANCE_TYPE_COMPLIANT = 'COMPLIANT';
//不合规
const COMPLIANCE_TYPE_NON_COMPLIANT = 'NON_COMPLIANT';
//不适用
const COMPLIANCE_TYPE_NOT_APPLICABLE = 'NOT_APPLICABLE';

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
      tagScopes
    }
  } = eventParams;

  if (!configurationItem) {
    logger.error(`There is no configurationItem in invokingEvent. Params is ${JSON.stringify(eventParams)}`);
    return;
  }

  const { tags, resourceId, accountId, regionId } = configurationItem;
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

  // 构造 oos 服务的 client
  const client = await getOosClient(eventParams, context);

  // 根据 oos 补丁基线进行扫描
  const {
    Execution: {
      ExecutionId: executionId,
    } 
  } = await startExecution(configurationItem, client);

  let execution;
  while (true) {
    execution = await getExecution(executionId, client);

    if (execution == null) {
      throw new Error(`The specified oos execution ${executionId} does not exist.`);
    }

    const { Status, StatusReason } = execution;
    switch (Status) {
      case 'Failed':
        logger.error(`The specified oos execution ${executionId} failed. Reason is ${StatusReason}.`);
        throw new Error(`The specified oos execution ${executionId} failed.`);
      case 'Cancelled':
        logger.error(`The specified oos execution ${executionId} has been cancelled.`);
        return;
      case 'Success':
        // 提交自定义函数规则的评估结果
        const {complianceType, annotation} = await getEvaluationResult(configurationItem, client, context);
        await putEvaluationResult(complianceType, annotation, eventParams, context);
        return;
    }

    await sleep(15000);
  }
}

async function getOosClient(eventParams, context) {
  const { regionId, accountId } = eventParams.invokingEvent.configurationItem;
  const { credentials } = context;

  // Assume Role 到需要检测的目标账号
  const stsClient = new RPCClient({
    accessKeyId: credentials.accessKeyId,
    accessKeySecret: credentials.accessKeySecret,
    securityToken: credentials.securityToken,
    endpoint: `https://sts.${regionId}.aliyuncs.com`,
    apiVersion: '2015-04-01',
  });

  const accountCredentials = await stsClient.request(
    'AssumeRole',
    {
      RegionId: regionId,
      RoleArn: `acs:ram::${accountId}:role/${eventParams.ruleParameters.configFcExecutionRoleName}`,
      RoleSessionName: 'EcsPatchBaselineInspection',
    },
    requestOption
  );

  // 构造 oos 服务的 client
  const oosClient = new RPCClient({
    accessKeyId: accountCredentials.Credentials.AccessKeyId,
    accessKeySecret: accountCredentials.Credentials.AccessKeySecret,
    securityToken: accountCredentials.Credentials.SecurityToken,
    endpoint: `https://oos.${regionId}.aliyuncs.com`,
    apiVersion: '2019-06-01',
  });

  return oosClient;
}

async function startExecution(configurationItem, client) {
  const { regionId, resourceId } = configurationItem;

  return await client.request(
    'StartExecution',
    {
      TemplateName: 'ACS-ECS-BulkyApplyPatchBaseline',
      Mode: 'Automatic',
      LoopMode: 'Automatic',
      SafetyCheck: 'Skip',
      Parameters: JSON.stringify({
        rebootIfNeed: false,
        OOSAssumeRole: '',
        regionId: regionId,
        action: 'scan',
        rateControl: {
          MaxErrors: 0,
          Concurrency: 1,
          Mode: 'Concurrency',
        },
        whetherCreateSnapshot: false,
        targets: {
          Type: 'ResourceIds',
          ResourceIds: [resourceId],
          RegionId: regionId,
        },
        resourceType: 'ALIYUN::ECS::Instance',
      }),
    },
    requestOption
  );
}

async function getExecution(executionId, client) {
  const executions = await client.request(
    'ListExecutions',
    {
      ExecutionId: executionId,
    },
    requestOption
  );
  return _.get(executions, 'Executions.0', null);
}

async function getEvaluationResult(configurationItem, client, context) {
  const { resourceId } = configurationItem;
  const { logger } = context;

  // 获取实例补丁状态
  const patchStates = await client.request(
    'ListInstancePatchStates',
    {
      InstanceIds: JSON.stringify([resourceId]),
    },
    requestOption
  );

  const patchState = _.get(patchStates, 'InstancePatchStates.0', null);
  if (patchState == null) {
    logger.error(`The patch info of instance ${resourceId} is empty.`);
    return;
  }

  let complianceType = '';
  let annotation = {};
  const {
    MissingCount = 0,
    FailedCount = 0,
    InstalledPendingRebootCount = 0,
    InstalledRejectedCount = 0,
  } = patchState;

  if (MissingCount == 0 && FailedCount == 0 && InstalledPendingRebootCount == 0 && InstalledRejectedCount == 0) {
    complianceType = COMPLIANCE_TYPE_COMPLIANT;
  } else {
    complianceType = COMPLIANCE_TYPE_NON_COMPLIANT;
    // 获取详细补丁信息
    const configuration = {
      missingCount: MissingCount,
      failedCount: FailedCount,
      installedPendingRebootCount: InstalledPendingRebootCount,
      installedRejectedCount: InstalledRejectedCount,
      missingPatches: [],
      failedPatches: [],
      installedPendingRebootPatches: [],
      installedRejectedPatches: [],
    };
    const patches = await listInstancePatches(resourceId, client);
    for (const patch of patches) {
      switch (patch.Status) {
        case 'Missing':
          configuration.missingPatches.push(patch);
          break;
        case 'InstalledPendingReboot':
          configuration.installedPendingRebootPatches.push(patch);
          break;
        case 'Failed':
          configuration.failedPatches.push(patch);
          break;
        case 'InstalledRejected':
          configuration.installedRejectedPatches.push(patch);
          break;
      }
    }
    annotation = {
      reason: `Not Installed: ${MissingCount}; Pending Restart: ${InstalledPendingRebootCount}; Install failed: ${FailedCount}; Installed Rejected Patch: ${InstalledRejectedCount};`,
      configuration: JSON.stringify(configuration),
    };
  }

  return {
    complianceType,
    annotation,
  };
}

async function listInstancePatches(resourceId, client) {
  let patches = [];
  let nextToken = '';

  while (true) {
    const res = await client.request(
      'ListInstancePatches',
      {
        InstanceId: resourceId,
        PatchStatuses: JSON.stringify([
          'Missing',
          'InstalledPendingReboot',
          'Failed',
          'InstalledRejected',
        ]),
        MaxResults: 100,
        NextToken: nextToken,
      },
      requestOption
    );
    const _patches = _.get(res, 'Patches', []);
    if (_.isEmpty(_patches)) {
      break;
    }
    patches = patches.concat(_patches);
    nextToken = _.get(res, 'NextToken', '');
    if (nextToken == null || nextToken == '') {
      break;
    }
  }
  
  return patches;
}

async function putEvaluationResult(complianceType, annotation, eventParams, context) {
  const {
    invokingEvent: {
      accountId,
      configurationItem: { regionId, resourceId, resourceType },
    },
    resultToken,
    orderingTimestamp,
  } = eventParams;

  const client = getConfigClient(context);

  return client.request('PutEvaluations', {
    ResultToken: resultToken,
    Evaluations: JSON.stringify([
      {
        accountId,
        annotation: JSON.stringify(annotation || {}),
        complianceResourceId: resourceId,
        complianceResourceType: resourceType,
        complianceRegionId: regionId,
        complianceType,
        orderingTimestamp,
      },
    ]),
    //启用删除模式
    DeleteMode: true
  }, requestOption);
}

function getConfigClient(context) {
  const { credentials } = context;
  return new RPCClient({
    accessKeyId: credentials.accessKeyId,
    accessKeySecret: credentials.accessKeySecret,
    securityToken: credentials.securityToken,
    endpoint: `https://config.${CONFIG_SERVICE_REGION}.aliyuncs.com`,
    apiVersion: '2020-09-07',
  });
}

function sleep(ms) {
  return new Promise((resolve, reject) => {
    setTimeout(resolve, ms);
  });
}