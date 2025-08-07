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

    // 从系统预留环境变量中获取 AK/SK/Security Token，初始化请求 OpenAPI 的 Client
    const client = new RPCClient({
        accessKeyId: process.env.ALIBABA_CLOUD_ACCESS_KEY_ID,
        accessKeySecret: process.env.ALIBABA_CLOUD_ACCESS_KEY_SECRET,
        securityToken: process.env.ALIBABA_CLOUD_SECURITY_TOKEN,
        endpoint: 'https://sts.cn-hangzhou.aliyuncs.com',
        apiVersion: '2015-04-01'
    });

    const result = await client.request('GetCallerIdentity', {
        pageSize: 1
    }, requestOption);
    return result;
}