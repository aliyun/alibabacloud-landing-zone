provider "alicloud" {
  region = var.region
}

resource "alicloud_vpc" "immediate_instance" {
  cidr_block = var.cidrVpc
}

resource "alicloud_vswitch" "immediate_instance" {
  cidr_block = var.cidrVSwitch
  zone_id    = var.zoneId
  vpc_id     = alicloud_vpc.immediate_instance.id
}

resource "alicloud_security_group" "immediate_instance" {
  vpc_id              = alicloud_vpc.immediate_instance.id
  security_group_type = "normal"
}

resource "alicloud_ram_policy" "service_role" {
  policy_name     = "GoldenImageAutomationServiceRolePolicy"
  policy_document = <<EOF
  {
    "Version": "1",
    "Statement": [
      {
        "Action": [
          "ecs:TagResources",
          "ecs:DescribeCloudAssistantStatus",
          "ecs:CreateImage",
          "ecs:InstallCloudAssistant",
          "ecs:RebootInstance",
          "ecs:DescribeInvocations",
          "ecs:RunCommand",
          "ecs:ModifyImageAttribute",
          "ecs:StopInstance",
          "ecs:DescribeInstances",
          "ecs:DescribeImages",
          "ecs:DeleteInstance",
          "ecs:RunInstances",
          "ecs:DescribeInvocationResults"
        ],
        "Resource": "*",
        "Effect": "Allow"
      },
      {
        "Action": [
          "yundun-sas:DescribeOnceTask",
          "yundun-sas:ExportVul",
          "yundun-sas:GetAuthSummary",
          "yundun-sas:DescribeAgentInstallStatus",
          "yundun-sas:OperateAgentClientInstall",
          "yundun-sas:CancelOnceTask",
          "yundun-sas:GetAssetDetailByUuid",
          "yundun-sas:DescribeVulExportInfo",
          "yundun-sas:RefreshAssets",
          "yundun-sas:DescribeCloudCenterInstances",
          "yundun-aegis:ModifyStartVulScan"
        ],
        "Resource": "*",
        "Effect": "Allow"
      }
    ]
  }
  EOF
}

resource "alicloud_ram_role" "service_role" {
  name     = "GoldenImageAutomationServiceRole"
  document = <<EOF
  {
    "Statement": [
      {
        "Action": "sts:AssumeRole",
        "Effect": "Allow",
        "Principal": {
          "Service": [
            "oos.aliyuncs.com"
          ]
        }
      }
    ],
    "Version": "1"
  }
  EOF
}

resource "alicloud_ram_role_policy_attachment" "service_role" {
  policy_name = alicloud_ram_policy.service_role.name
  policy_type = alicloud_ram_policy.service_role.type
  role_name   = alicloud_ram_role.service_role.name
}

resource "alicloud_oos_template" "golden_image_automation" {
  template_name = "GoldenImageAutomation"
  content       = <<EOF
  FormatVersion: OOS-2019-06-01
  Description:
    name-en: GoldenImageAutomation
    name-zh-cn: 自动化构建 Golden Image
    en: This automation template triggers Golden Image creation workflow.
    zh-cn: 该自动化模版用来触发 Golden Image 的构建流程。
  Parameters:
    sourceImageId:
      Label:
        en: SourceImageId
        zh-cn: 源镜像ID
      Type: String
      AssociationProperty: ALIYUN::ECS::Image::ImageId
      AssociationPropertyMetadata:
        RegionId: ACS::RegionId
    imageFamily:
      Label:
        en: ImageFamily
        zh-cn: 镜像族系
      Type: String
      Description:
        en: Configure the image family of the golden image to aggregate a group of golden images for the same purpose. The name must be 2 to 128 characters in length and can contain digits, colons (:), underscores (_), and hyphens (-). The name must start with a letter but cannot start with http:// or https://.
        zh-cn: 配置新构建的 Golden Image 的镜像族系，用来聚合一组同一用途的 Golden Image。长度为2~128个英文或中文字符。必须以大小写字母或中文开头，不能以aliyun和acs:开头，不能包含http://或者https://。可以包含数字、半角冒号（:）、下划线（_）或者短划线（-）。
      Default: '${var.imageFamily}'
    imageOSAndVersion:
      Label:
        en: ImageOSAndVersion
        zh-cn: 镜像OS信息
      Type: String
      Description:
        en: Operating system name and OS version. The syntax of this parameter is OSName-OSVersion.
        zh-cn: Golden Image 的OS详情。建议格式为 OSName-OSVersion
      Default: '${var.imageOSAndVersion}'
    imageVersion:
      Label:
        en: ImageVersion
        zh-cn: 镜像版本
      Type: String
      Description:
        en: The version number of the golden image to be created. Typically, you will increment this number every time you create a new version for an existing image.
        zh-cn: Golden Image 的版本号。每次更新 Golden Image 时，需要同时升级版本号。
      Default: '${var.imageVersion}'
    targetImageName:
      Label:
        en: TargetImageName
        zh-cn: 新镜像的名称
      Type: String
      Description:
        en: Please use the default value directly. Length is 2~128 English or Chinese characters. Must start with big or small letters or Chinese, not http:// and https://. Can contain numbers, colons (:), underscores (_), or dashes (-).
        zh-cn: 建议直接使用默认值。长度为2~128个英文或中文字符。必须以大小字母或中文开头，不能以http://和https://开头。可以包含数字、半角冒号（:）、下划线（_）或者短划线（-）。
      Default: '{{imageFamily}}-{{imageOSAndVersion}}-{{imageVersion}}'
    zoneId:
      Type: String
      Label:
        en: AvailabilityZone
        zh-cn: 可用区
      AssociationProperty: ALIYUN::ECS::Instance::ZoneId
      AssociationPropertyMetadata:
        RegionId: ACS::RegionId
      Default: '${var.zoneId}'
    instanceType:
      Label:
        en: InstanceType
        zh-cn: 实例类型
      Type: String
      AssociationProperty: ALIYUN::ECS::Instance::InstanceType
      AssociationPropertyMetadata:
        RegionId: ACS::RegionId
        ZoneId: zoneId
      Default: '${var.instanceType}'
    securityGroupId:
      Label:
        en: SecurityGroupId
        zh-cn: 安全组ID
      Type: String
      AssociationProperty: ALIYUN::ECS::SecurityGroup::SecurityGroupId
      AssociationPropertyMetadata:
        RegionId: ACS::RegionId
      Default: '${alicloud_security_group.immediate_instance.id}'
    vSwitchId:
      Label:
        en: VSwitchId
        zh-cn: 交换机ID
      Type: String
      AssociationProperty: ALIYUN::VPC::VSwitch::VSwitchId
      AssociationPropertyMetadata:
        RegionId: ACS::RegionId
        ZoneId: zoneId
        InstanceType: instanceType
        Filters:
          - SecurityGroupId: securityGroupId
      Default: '${alicloud_vswitch.immediate_instance.id}'
    internetMaxBandwidthOut:
      Type: Number
      Label:
        zh-cn: 流量公网带宽
        en: InternetMaxBandwidthOut
      Description:
        zh-cn: 取值范围0-100, 0为不开公网ip
        en: 'Unit: Mbit/s. Valid values: 0 to 100. No public ip if zero'
      Default: ${var.internetMaxBandwidthOut}
      MinValue: 0
      MaxValue: 100
    systemDiskCategory:
      Label:
        en: SystemDiskCategory
        zh-cn: 系统盘的云盘种类
      Type: String
      AssociationProperty: ALIYUN::ECS::Disk::SystemDiskCategory
      AssociationPropertyMetadata:
        RegionId: ACS::RegionId
        InstanceType: instanceType
      Default: cloud_essd
    ramRoleName:
      Label:
        en: RamRoleName
        zh-cn: 实例的RAM角色
      Type: String
      AssociationProperty: ALIYUN::ECS::RAM::Role
      Default: ''
    commandType:
      Label:
        en: CommandType
        zh-cn: 云助手命令类型
      Type: String
      AssociationPropertyMetadata:
        LocaleKey: PricingCycle
      AllowedValues:
        - RunBatScript
        - RunPowerShellScript
        - RunShellScript
      Default: RunShellScript
    commandContent:
      Label:
        en: CommandContent
        zh-cn: 在ECS实例中执行的云助手命令
      Type: String
      AssociationProperty: Code
      Default: echo hello
    timeout:
      Label:
        en: CommandTimeout
        zh-cn: 命令执行超时时间
      Type: Number
      Default: 3600
    OOSAssumeRole:
      Label:
        en: OOSAssumeRole
        zh-cn: OOS扮演的RAM角色
      Type: String
      Default: '${alicloud_ram_role.service_role.name}'
    whetherInspectImage:
      Type: Boolean
      Label:
        en: WhetherInspectImage
        zh-cn: 是否对镜像进行漏洞扫描
      Description:
        en: 'Please ensure that your security center is upgraded to any of the following versions: Advanced, Enterprise or Ultimate.'
        zh-cn: 请保证您的云安全中心已经升级到如下任意一个版本：高级版、企业版或者旗舰版。
      Default: true
    whetherApprove:
      Type: Boolean
      Label:
        en: WhetherApprove
        zh-cn: 是否需要人工审批
      Default: true
    approverUser:
      Type: String
      Label:
        en: ApproverUser
        zh-cn: 审批人
      Description:
        en: RAM user allowed for approval. This RAM user needs to have corresponding read and write permissions, and you can directly grant it AliyunOOSFullAccess permissions. This RAM user can approve/deny golden images.
        zh-cn: 允许审批的 RAM 用户。该 RAM 用户需要具备相应的读写权限，您可以直接授予其 AliyunOOSFullAccess 权限。该 RAM 用户可以批准/拒绝 Golden Image。
      Default: '${var.approverRamUserName}'
      AssociationProperty: ALIYUN::RAM::User
      AssociationPropertyMetadata:
        Visible:
          Condition:
            Fn::Equals:
              - $${whetherApprove}
              - true
    webHookUrl:
      Type: String
      Label:
        en: WebHookUrl
        zh-cn: WebHook地址
      Description:
        en: When image vulnerability assessment or manual approval is enabled, a notification will be sent through this WebHook.
        zh-cn: 开启镜像漏洞扫描或者需要人工审批时，会通过该WebHook发送通知。
      Default: '${var.webHookUrl}'
      AssociationPropertyMetadata:
        Visible:
          Condition:
            Fn::Or:
              - Fn::Equals:
                - $${whetherApprove}
                - true
              - Fn::Equals:
                - $${whetherInspectImage}
                - true
    atMobiles:
      Type: List
      Label:
        en: AtMobiles
        zh-cn: 需要@的指定用户
      Description:
        en: Only DingTalk notifications are supported. The telephone numbers of member in dingtalk group assistant @, when notify comes.
        zh-cn: 只支持钉钉通知。当群助手向钉钉群中发送审批通知时，要被@的群成员注册钉钉所用手机号。
      Default: []
      AssociationPropertyMetadata:
        Visible:
          Condition:
            Fn::Equals:
              - $${whetherApprove}
              - true
  RamRole: '{{ OOSAssumeRole }}'
  Tasks:
    - Name: checkTargetImageName
      Action: ACS::CheckFor
      Description:
        en: Check image name is available
        zh-cn: 检查镜像名称可用
      Properties:
        Service: ECS
        API: DescribeImages
        Parameters:
          ImageName: '{{ targetImageName }}'
        DesiredValues:
          - 0
        PropertySelector: TotalCount
    - Name: checkSasVersion
      Action: ACS::CheckFor
      When:
        Fn::Equals:
          - '{{ whetherInspectImage }}'
          - true
      Description:
        en: Check security center version is available
        zh-cn: 检查云安全中心版本
      Properties:
        Service: SAS
        API: GetAuthSummary
        Parameters: {}
        DesiredValues:
          - 5
          - 3
          - 7
        PropertySelector: HighestVersion
    - Name: runInstance
      Action: ACS::ECS::RunInstances
      Description:
        en: Create a ECS instance with source image
        zh-cn: 使用源镜像创建一台ECS实例
      Properties:
        imageId: '{{ sourceImageId }}'
        instanceType: '{{ instanceType }}'
        securityGroupId: '{{ securityGroupId }}'
        vSwitchId: '{{ vSwitchId }}'
        internetMaxBandwidthOut: '{{ internetMaxBandwidthOut }}'
        ramRoleName: '{{ ramRoleName }}'
        systemDiskCategory: '{{ systemDiskCategory }}'
      Outputs:
        instanceId:
          ValueSelector: instanceIds[0]
          Type: String
    - Name: installCloudAssistant
      Action: ACS::ECS::InstallCloudAssistant
      Description:
        en: Install cloud assistant for ECS instance
        zh-cn: 给实例安装云助手
      OnError: deleteInstance
      Properties:
        instanceId: '{{ runInstance.instanceId }}'
    - Name: runCommand
      Action: ACS::ECS::RunCommand
      Description:
        en: Run cloud assistant command on ECS instance
        zh-cn: 在实例中运行云助手命令
      OnError: deleteInstance
      Properties:
        commandContent: '{{ commandContent }}'
        commandType: '{{ commandType }}'
        instanceId: '{{ runInstance.instanceId }}'
        timeout: '{{ timeout }}'
    - Name: stopInstance
      Action: ACS::ECS::StopInstance
      Description:
        en: Stops the ECS instance
        zh-cn: 停止ECS实例
      Properties:
        instanceId: '{{ runInstance.instanceId }}'
    - Name: createImage
      Action: ACS::ECS::CreateImage
      Description:
        en: Create new image
        zh-cn: 创建新镜像
      OnError: deleteInstance
      Properties:
        imageName: '{{ targetImageName }}'
        instanceId: '{{ runInstance.instanceId }}'
      Outputs:
        imageId:
          ValueSelector: imageId
          Type: String
    - Name: deleteInstance
      Action: ACS::ExecuteAPI
      Description:
        en: Deletes the ECS instance
        zh-cn: 删除ECS实例
      Properties:
        Service: ECS
        API: DeleteInstance
        Risk: Normal
        Parameters:
          InstanceId: '{{ runInstance.instanceId }}'
          Force: true
    - Name: runInspectorInstance
      Action: ACS::ECS::RunInstances
      When:
        Fn::Equals:
          - '{{ whetherInspectImage }}'
          - true
      Description:
        en: Create a ECS instance with new image
        zh-cn: 使用新构建的镜像创建一台ECS实例
      Properties:
        imageId: '{{ createImage.imageId }}'
        instanceType: '{{ instanceType }}'
        securityGroupId: '{{ securityGroupId }}'
        vSwitchId: '{{ vSwitchId }}'
        internetMaxBandwidthOut: '{{ internetMaxBandwidthOut }}'
        ramRoleName: '{{ ramRoleName }}'
        systemDiskCategory: '{{ systemDiskCategory }}'
      Outputs:
        instanceId:
          ValueSelector: instanceIds[0]
          Type: String
    - Name: installCloudAssistantForInspectorInstance
      Action: ACS::ECS::InstallCloudAssistant
      When:
        Fn::Equals:
          - '{{ whetherInspectImage }}'
          - true
      Description:
        en: Install cloud assistant for vulnerability assessment ECS instance
        zh-cn: 给漏洞扫描实例安装云助手
      OnError: deleteInspectorInstance
      Properties:
        instanceId: '{{ runInspectorInstance.instanceId }}'
    - Name: syncInspectorInstance
      Action: ACS::ExecuteAPI
      When:
        Fn::Equals:
          - '{{ whetherInspectImage }}'
          - true
      Description:
        en: Sync vulnerability assessment ECS instance to security center
        zh-cn: 同步漏洞扫描实例到云安全中心
      OnError: deleteInspectorInstance
      Properties:
        Service: SAS
        API: RefreshAssets
        Parameters:
          AssetType: 'ecs'
          Vendor: '0'
    - Name: waitForSync
      Action: ACS::WaitFor
      When:
        Fn::Equals:
          - '{{ whetherInspectImage }}'
          - true
      Description:
        en: Wait for sync to complete
        zh-cn: 等待同步完成
      OnError: deleteInspectorInstance
      Properties: 
        Service: SAS
        API: DescribeCloudCenterInstances
        Parameters: 
          MachineTypes: 'ecs'
          Criteria: '[{"name":"instanceId","value":"{{ runInspectorInstance.instanceId }}"}]'
          PageSize: 1
        PropertySelector: 'PageInfo.Count'
        DesiredValues:
          - 1
      Retries: 60
      DelayType: 'Constant'
      Delay: 10
      Outputs:
        uuid:
          ValueSelector: 'Instances[0].Uuid'
          Type: String
        os:
          ValueSelector: 'Instances[0].Os'
          Type: String
    - Name: installInspectorClient
      Action: ACS::ExecuteAPI
      When:
        Fn::Equals:
          - '{{ whetherInspectImage }}'
          - true
      Description:
        en: Install security center client for vulnerability assessment ECS instance
        zh-cn: 给漏洞扫描实例安装云安全Client
      OnError: deleteInspectorInstance
      Properties:
        Service: SAS
        API: OperateAgentClientInstall
        Parameters:
          Uuids: '{{ waitForSync.uuid }}'
          InstanceIds: '{{ runInspectorInstance.instanceId }}'
    - Name: waitForInstall
      Action: ACS::WaitFor
      When:
        Fn::Equals:
          - '{{ whetherInspectImage }}'
          - true
      Description:
        en: Wait for installation to complete
        zh-cn: 等待安装完成
      OnError: deleteInspectorInstance
      Properties: 
        Service: SAS
        API: DescribeAgentInstallStatus
        Parameters: 
          Uuids: '{{ waitForSync.uuid }}'
        PropertySelector: 'AegisClientInvokeStatusResponseList[0].ResuleCode'
        DesiredValues:
          - '0'
          - '1010'
        StopRetryValues:
          - '1'
          - '2'
          - '3'
          - '4'
          - '5'
          - '6'
          - '7'
          - '100'
          - '1001'
          - '1003'
          - '1004'
          - '1007'
      Retries: 60
      DelayType: 'Constant'
      Delay: 10
    - Name: waitForClientOnline
      Action: ACS::WaitFor
      When:
        Fn::Equals:
          - '{{ whetherInspectImage }}'
          - true
      Description:
        en: Wait for client online
        zh-cn: 等待客户端上线
      OnError: deleteInspectorInstance
      Properties: 
        Service: SAS
        API: GetAssetDetailByUuid
        Parameters: 
          Uuid: '{{ waitForSync.uuid }}'
        PropertySelector: 'AssetDetail.ClientStatus'
        DesiredValues:
          - online
      Retries: 60
      DelayType: 'Constant'
      Delay: 10
    - Name: sleep
      Action: ACS::Sleep
      When:
        Fn::Equals:
          - '{{ whetherInspectImage }}'
          - true
      Properties:
        Duration: PT3M
    - Name: getLatestInspectTask
      Action: ACS::ExecuteAPI
      When:
        Fn::Equals:
          - '{{ whetherInspectImage }}'
          - true
      Description:
        en: Get the latest vulnerability assessment task
        zh-cn: 获取最新的漏洞扫描任务
      OnError: deleteInspectorInstance
      Properties:
        Service: SAS
        API: DescribeOnceTask
        Parameters:
          TaskType: 'VUL_CHECK_TASK'
      Outputs:
        taskId: 
          ValueSelector: TaskManageResponseList[0].TaskId
          Type: String
        taskStatus: 
          ValueSelector: TaskManageResponseList[0].TaskStatus
          Type: Number
    - Name: validLatestInspectTask
      Action: ACS::ExecuteAPI
      When:
        Fn::If:
          - Fn::Equals:
            - '{{ whetherInspectImage }}'
            - true
          - Fn::Equals:
            - '{{ getLatestInspectTask.taskStatus }}'
            - 1
          - false
      Description:
        en: Determine whether the latest running vulnerability assessment task is valid
        zh-cn: 判断最新的正在运行的漏洞扫描任务是否有效
      OnError: deleteInspectorInstance
      Properties:
        Service: SAS
        API: DescribeOnceTask
        AutoPaging: true
        Parameters:
          TaskType: 'VUL_CHECK_TASK'
          RootTaskId: '{{ getLatestInspectTask.taskId }}'
          PageSize: 100
      Outputs:
        isValid:
          ValueSelector: '.TaskManageResponseList | map(select(.DetailData == "[]" and .TaskStatus == 1)) | length == 0'
          Type: Boolean
    - Name: cancelLatestInspectTask
      Action: ACS::ExecuteAPI
      When:
        Fn::If:
          - Fn::Equals:
            - '{{ whetherInspectImage }}'
            - true
          - Fn::If:
            - Fn::Equals:
              - '{{ getLatestInspectTask.taskStatus }}'
              - 1
            - Fn::Equals:
              - '{{ validLatestInspectTask.isValid }}'
              - false
            - false
          - false
      Description:
        en: Cancel latest invalid vulnerability assessment task that is running
        zh-cn: 取消最新的正在运行的无效漏洞扫描任务
      OnError: deleteInspectorInstance
      Properties:
        Service: SAS
        API: CancelOnceTask
        Parameters:
          TaskId: '{{ getLatestInspectTask.taskId }}'
    - Name: waitForLatestInspect
      Action: ACS::WaitFor
      When:
        Fn::If:
          - Fn::Equals:
            - '{{ whetherInspectImage }}'
            - true
          - Fn::If:
            - Fn::Equals:
              - '{{ getLatestInspectTask.taskStatus }}'
              - 1
            - Fn::Equals:
              - '{{ validLatestInspectTask.isValid }}'
              - true
            - Fn::Equals:
              - '{{ getLatestInspectTask.taskStatus }}'
              - 0
          - false
      Description:
        en: Wait for current vulnerability assessment to complete
        zh-cn: 等待当前正在运行的漏洞扫描完成
      OnError: deleteInspectorInstance
      Properties: 
        Service: SAS
        API: DescribeOnceTask
        Parameters: 
          TaskType: 'VUL_CHECK_TASK'
          TaskId: '{{ getLatestInspectTask.taskId }}'
        PropertySelector: 'TaskManageResponseList[0].TaskStatus'
        DesiredValues:
          - 2
        StopRetryValues:
          - 3
          - 4
      Retries: 120
      DelayType: 'Constant'
      Delay: 30
    - Name: startInspect
      Action: ACS::ExecuteAPI
      When:
        Fn::Equals:
          - '{{ whetherInspectImage }}'
          - true
      Description:
        en: Start vulnerability assessment
        zh-cn: 开始漏洞扫描
      OnError: deleteInspectorInstance
      Properties:
        Service: SAS
        API: ModifyStartVulScan
        Parameters:
          Types: 'cve,sys'
          Uuids: '{{ waitForSync.uuid }}'
      Outputs: {}
    - Name: waitForInspect
      Action: ACS::WaitFor
      When:
        Fn::Equals:
          - '{{ whetherInspectImage }}'
          - true
      Description:
        en: Wait for vulnerability assessment to complete
        zh-cn: 等待漏洞扫描完成
      OnError: deleteInspectorInstance
      Properties: 
        Service: SAS
        API: DescribeOnceTask
        Parameters: 
          TaskType: 'VUL_CHECK_TASK'
        PropertySelector: 'TaskManageResponseList[0].TaskStatus'
        DesiredValues:
          - 2
        StopRetryValues:
          - 3
          - 4
      Retries: 120
      DelayType: 'Constant'
      Delay: 30
    - Name: exportVul
      Action: ACS::ExecuteAPI
      When:
        Fn::Equals:
          - '{{ whetherInspectImage }}'
          - true
      Description:
        en: Export vulnerability assessment report
        zh-cn: 导出漏洞检测报告
      OnError: deleteInspectorInstance
      Properties:
        Service: SAS
        API: ExportVul
        Parameters:
          Type:
            Fn::If:
              - Fn::Equals:
                - '{{ waitForSync.os }}'
                - 'linux'
              - 'cve'
              - 'sys'
          Uuids: '{{ waitForSync.uuid }}'
      Outputs:
        exportId:
          ValueSelector: 'Id'
          Type: String
    - Name: waitForExport
      Action: ACS::WaitFor
      When:
        Fn::Equals:
          - '{{ whetherInspectImage }}'
          - true
      Description:
        en: Wait for export to complete
        zh-cn: 等待导出完成
      OnError: deleteInspectorInstance
      Properties: 
        Service: SAS
        API: DescribeVulExportInfo
        Parameters: 
          ExportId: '{{ exportVul.exportId }}'
        PropertySelector: 'ExportStatus'
        DesiredValues:
          - 'success'
      Retries: 60
      DelayType: 'Constant'
      Delay: 10
      Outputs:
        vulLink:
          ValueSelector: 'Link'
          Type: String
    - Name: deleteInspectorInstance
      Action: ACS::ExecuteAPI
      When:
        Fn::Equals:
          - '{{ whetherInspectImage }}'
          - true
      Description:
        en: Deletes the vulnerability assessment ECS instance
        zh-cn: 删除漏洞扫描实例
      Properties:
        Service: ECS
        API: DeleteInstance
        Risk: Normal
        Parameters:
          InstanceId: '{{ runInspectorInstance.instanceId }}'
          Force: true
    - Name: nofity
      Action: ACS::Notify
      When:
        Fn::And:
          - Fn::Equals:
            - '{{ whetherInspectImage }}'
            - true
          - Fn::Equals:
            - '{{ whetherApprove }}'
            - false
      Description:
        en: Send vulnerability assessment report notification
        zh-cn: 发送漏洞报告通知
      Properties:
        NotifyType: WebHook
        WebHook:
          URI: '{{ webhookUrl }}'
          Headers:
            Content-Type: 'application/json; charset=utf-8'
          Content:
            msgtype: markdown
            markdown: 
              title: Golden Image Automation
              text: >-
                ### Golden Image Automation

                Please [download the vulnerability assessment report]({{ waitForExport.vulLink }}) for this golden image: [{{ targetImageName }}](https://ecs.console.aliyun.com/imageDetail/region/{{ ACS::RegionId }}/imageId/{{ createImage.imageId }}) and review the assessment result.
    - Name: approve
      Action: ACS::Approve
      When:
        Fn::Equals:
          - '{{ whetherApprove }}'
          - true
      Description:
        en: Waiting for manual approval
        zh-cn: 等待人工审批
      Properties:
        Approvers: 
          Fn::If:
            - Fn::Equals:
              - '{{ approverUser }}'
              - ''
            - []
            - - '{{ approverUser }}'
        NotifyType: WebHook
        WebHook:
          URI: '{{ webhookUrl }}'
          Headers:
            Content-Type: application/json
          Content:
            msgtype: markdown
            markdown:
              title: Golden Image Automation
              text: 
                Fn::If:
                  - Fn::Equals:
                    - '{{ whetherInspectImage }}'
                    - true
                  - >-
                    ### Golden Image Automation

                    Please [download the vulnerability assessment report]({{ waitForExport.vulLink }}) for this golden image: [{{ targetImageName }}](https://ecs.console.aliyun.com/imageDetail/region/{{ ACS::RegionId }}/imageId/{{ createImage.imageId }}) and review the assessment result.
                    
                    Then please approve/deny the golden image build, sent by {{ ACS::RegionId }} oos {{ ACS::ExecutionId }}.
                  - >-
                    ### Golden Image Automation

                    Please approve/deny the golden image: [{{ targetImageName }}](https://ecs.console.aliyun.com/imageDetail/region/{{ ACS::RegionId }}/imageId/{{ createImage.imageId }}) build, sent by {{ ACS::RegionId }} oos {{ ACS::ExecutionId }}.
            at:
              atMobiles: '{{ atMobiles }}'
    - Name: tagImage
      Action: ACS::ExecuteApi
      Description:
        en: Tag the new golden image
        zh-cn: 给新的镜像打标签
      Properties:
        Service: ECS
        API: TagResources
        Parameters:
          ResourceIds:
            - '{{ createImage.imageId }}'
          ResourceType: 'image'
          Tags:
            - Key: ImageFamily
              Value: '{{ imageFamily }}'
            - Key: ImageOSAndVersion
              Value: '{{ imageOSAndVersion }}'
            - Key: ImageVersion
              Value: '{{ imageVersion }}'
            - Key: ImageType
              Value: Golden
    - Name: setImageFamily
      Action: ACS::ExecuteApi
      Description:
        en: Set the image family for the new golden image
        zh-cn: 给新的镜像设置镜像族系
      Properties:
        Service: ECS
        API: ModifyImageAttribute
        Parameters:
          ImageId: '{{ createImage.imageId }}'
          ImageFamily: '{{ imageFamily }}'
  Outputs:
    imageId:
      Type: String
      Value: '{{ createImage.imageId }}'
  Metadata:
    ALIYUN::OOS::Interface:
      ParameterGroups:
        - Parameters:
            - sourceImageId
          Label:
            default:
              zh-cn: 选择源镜像
              en: Select Origin Image
        - Parameters:
            - imageFamily
            - imageOSAndVersion
            - imageVersion
            - targetImageName
          Label:
            default:
              zh-cn: 镜像设置
              en: Image Configure
        - Parameters:
            - zoneId
            - instanceType
            - securityGroupId
            - vSwitchId
            - internetMaxBandwidthOut
            - systemDiskCategory
            - ramRoleName
          Label:
            default:
              zh-cn: 配置中转实例
              en: ECS Instance Configure
        - Parameters:
            - commandType
            - commandContent
            - timeout
          Label:
            default:
              zh-cn: 发送远程命令
              en: Run Command
        - Parameters:
            - whetherInspectImage
            - whetherApprove
            - approverUser
            - webHookUrl
            - atMobiles
          Label:
            default:
              zh-cn: 漏洞扫描与审批
              en: Vulnerability Assessment And Approval
        - Parameters:
            - OOSAssumeRole
          Label:
            default:
              zh-cn: 高级选项
              en: Control Options
  EOF

  depends_on = [
    alicloud_security_group.immediate_instance,
    alicloud_vswitch.immediate_instance
  ]
}
