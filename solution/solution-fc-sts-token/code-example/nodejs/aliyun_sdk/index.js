'use strict';

const RPCClient = require('@alicloud/pop-core').RPCClient;
const httpModule = require('https');

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
    main(context)
        .then((res) => {
            callback(null, JSON.stringify(res));
        })
        .catch((err) => callback(err));
};

async function main(context) {
    const { credentials, logger } = context;

    // 从上下文中获取 AK/SK/Security Token，初始化请求 OpenAPI 的 Client
    const client = new RPCClient({
        accessKeyId: credentials.accessKeyId,
        accessKeySecret: credentials.accessKeySecret,
        securityToken: credentials.securityToken,
        endpoint: 'https://sts.cn-hangzhou.aliyuncs.com',
        apiVersion: '2015-04-01'
    });

    const result = await client.request('GetCallerIdentity', {
        pageSize: 1
    }, requestOption);
    return result;
}