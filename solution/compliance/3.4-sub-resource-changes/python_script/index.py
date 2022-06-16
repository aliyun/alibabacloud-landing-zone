import pymysql
import json,logging
import os
connection = None
logger = logging.getLogger()
def initialize(context):
    global connection
    try:
        connection = pymysql.connect(
            host=os.environ['MYSQL_ENDPOING'],  # 替换为您的HOST名称。
            port=int(os.environ['MYSQL_PORT']),  # 替换为您的端口号。
            user=os.environ['MYSQL_USER'],  # 替换为您的用户名。
            passwd=os.environ['MYSQL_PASSWORD'],  # 替换为您的用户名对应的密码。
            db=os.environ['MYSQL_DBNAME'],  # 替换为您的数据库名称。
            connect_timeout=5)
        logger.info('eb job connect mysql success!!!')
    except Exception as e:
        logger.error(
            "ERROR: Unexpected error: Could not connect to MySql instance.")
        raise Exception(str(e))

def pre_stop(context):
    if connection != None:
        connection.close()

def save_transactional(sql, params):
    try:
        cursor=connection.cursor()
        cursor.execute(sql , params)
        connection.commit()
    except Exception as e:
        logger.error(e)

def handler(event, context):
    eb = json.loads(event).get("data")
    eb_action,eb_time,resource_name,req_id = eb.get("resourceEventType"),eb.get("captureTime"),eb.get("resourceName"),eb.get("requestId")
    account_id, region, config_diff, resourse_type = eb.get("accountId"),eb.get("regionId"),eb.get("configurationDiff"),eb.get("resourceType")
    logger.info(eb_action+eb_time+resource_name+req_id+account_id+region+config_diff+resourse_type)
    change_sql = "insert into event_list(req_id,type,time,resource,account,region,configdiff,resourcetype) values(%s,%s,%s,%s,%s,%s,%s,%s)"
    params = (req_id,eb_action,eb_time,resource_name,account_id, region, config_diff, resourse_type)
    save_transactional(change_sql, params)
    return 'success'