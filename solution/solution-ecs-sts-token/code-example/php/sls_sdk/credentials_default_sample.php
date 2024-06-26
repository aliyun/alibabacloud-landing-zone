<?php
require 'vendor/autoload.php';
use AlibabaCloud\Credentials\Credential;

echo "Hello, World!";
class CredentialsAdapter implements Aliyun_Log_Models_CredentialsProvider
{
    /**
     * @var Credential
     */
    private $_credentials;
    public function __construct(Credential $credentials)
    {
        $this->_credentials = $credentials;
    }
    public function getCredentials(): Aliyun_Log_Models_Credentials
    {
        return new Aliyun_Log_Models_Credentials(
            $this->_credentials->getAccessKeyId(),
            $this->_credentials->getAccessKeySecret(),
            $this->_credentials->getSecurityToken()
        );
    }
};

# 设置参数
$endpoint = 'cn-hangzhou.log.aliyuncs.com';
$project = 'your-project';
$logstore = 'your-logstore';
$credentials = new Credential([
    'type' => 'ecs_ram_role',
    'role_name' => 'ecs-ak-role',
]);

# 构造 client
$credentialsProvider = new CredentialsAdapter($credentials);
$client = new Aliyun_Log_Client($endpoint, "", "", "", $credentialsProvider);


# 发请求
$req = new Aliyun_Log_Models_GetLogsRequest($project, $logstore, 1698740109, 1698744321, '', '*', null, null, null, null);
function putLogs(Aliyun_Log_Client $client, $project, $logstore) {
  $topic = 'TestTopic';

  $contents = array(
      'TestKey'=>'TestContent',
      'kv_json'=>'{"a": "b", "c": 19021}'
  );
  $logItem = new Aliyun_Log_Models_LogItem();
  $logItem->setTime(time());
  $logItem->setContents($contents);
  $logitems = array($logItem);
  $request = new Aliyun_Log_Models_PutLogsRequest($project, $logstore,
          $topic, null, $logitems);

  try {
      $response = $client->putLogs($request);
  } catch (Aliyun_Log_Exception $ex) {
      var_dump($ex);
  } catch (Exception $ex) {
      var_dump($ex);
  }
}

putLogs($client, $project, $logstore);
var_dump($client->getLogs($req)->getLogs());