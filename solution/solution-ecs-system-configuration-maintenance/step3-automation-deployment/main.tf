provider "alicloud" {
  region = var.region
}

# create command running automation template
resource "alicloud_oos_template" "share_services" {
  template_name = "EcsCommandRunningAutomation"
  content       = <<EOF
  FormatVersion: OOS-2019-06-01
  Description:
    en: This automation template triggers ECS commond running workflow.
    zh-cn: 该自动化模版用来在ECS主机实例上执行运维命令。
    name-en: EcsCommandRunningAutomation
    name-zh-cn: 主机命令运行自动化运维
  Parameters:
    accountRoleAndRegions:
      Label:
        en: AccountRoleAndRegions
        zh-cn: 指定目标账号和地域
      Type: Json
      AssociationProperty: List[Parameters]
      AssociationPropertyMetadata:
        Parameters:
          accountId:
            Type: String
            Label:
              en: AccountId
              zh-cn: 目标账号
          regionIds:
            Label:
              en: RegionIds
              zh-cn: 目标地域
            Type: Json
            AssociationProperty: List[Parameter]
            AssociationPropertyMetadata:
              Parameter:
                regionId:
                  Type: String
                  AssociationProperty: ALIYUN::ECS::RegionId
    templateName:
      Type: String
      Default: ACS-ECS-BulkyRunCommand
      AssociationProperty: TemplateName
      AllowedValues:
        - ACS-ECS-BulkyRunCommand
      Label:
        en: TemplateName
        zh-cn: 任务模版
    templateParameters:
      Label:
        en: TemplateParemeters
        zh-cn: 模版参数
      Type: Json
      AssociationProperty: TemplateParameter
      AssociationPropertyMetadata:
        TemplateName: $templateName
    rateControl:
      Label:
        en: RateControl
        zh-cn: 账号执行的并发比率
      Description:
        en: Control the concurrency ratio of the accounts.
        zh-cn: 控制账号维度的并发比率。
      Type: Json
      AssociationProperty: RateControl
      Default:
        Mode: Batch
        MaxErrors: 0
        Batch: [1, 25%, 50%]
        BatchPauseOption: EveryBatchPause
    approvalRequired:
      Type: Boolean
      Label:
        en: ApprovalRequired
        zh-cn: 是否需要人工审批
      Default: true
    approverUser:
      Type: String
      Label:
        en: ApproverUser
        zh-cn: 审批人
      Description:
        en: RAM user allowed for approval. This RAM user needs to have corresponding read and write permissions, and you can directly grant it AliyunOOSFullAccess permissions. This RAM user can approve/deny execute command running.
        zh-cn: 允许审批的 RAM 用户。该 RAM 用户需要具备相应的读写权限，您可以直接授予其 AliyunOOSFullAccess 权限。该 RAM 用户可以批准/拒绝补丁运维流程。
      Default: '${var.approverRamUserName}'
      AssociationProperty: ALIYUN::RAM::User
      AssociationPropertyMetadata:
        Visible:
          Condition:
            Fn::Equals:
              - $${approvalRequired}
              - true
    approverWebHookUrl:
      Type: String
      Label:
        en: ApproverWebHookUrl
        zh-cn: 审批通知WebHook地址
      Description:
        en: When manual approval is enabled, a notification will be sent through this WebHook.
        zh-cn: 需要人工审批时，会通过该WebHook发送通知。
      Default: '${var.approverWebHookUrl}'
      AssociationPropertyMetadata:
        Visible:
          Condition:
            Fn::Or:
              - Fn::Equals:
                - $${approvalRequired}
                - true
    commandRunningWebHookUrl:
      Type: String
      Label:
        en: CommandRunningWebHookUrl
        zh-cn: 运维通知WebHook地址
      Description:
        en: When execute command running, a notification will be sent through this WebHook.
        zh-cn: 命令运行前，会通过该WebHook发送通知。
      Default: '${var.commandRunningWebHookUrl}'
    OOSAssumeRole:
      Label:
        en: The RAM role to be assumed by OOS
        zh-cn: OOS扮演的RAM角色
      Type: String
      Default: '${var.oss_assume_role}'
  RamRole: '{{ OOSAssumeRole }}'
  Tasks:
    - Name: approve
      Action: ACS::Approve
      When:
        Fn::Equals:
          - '{{ approvalRequired }}'
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
          URI: '{{ approverWebHookUrl }}'
          Headers:
            Content-Type: 'application/json; charset=utf-8'
          Content:
            msgtype: markdown
            markdown:
              title: ECS Command Running Automation
              text: >-
                ### ECS Command Running Automation

                Please approve/deny the ecs command running automation, sent by {{ ACS::RegionId }} oos {{ ACS::ExecutionId }}.
    - Name: nofity
      Action: ACS::Notify
      When:
        Fn::Not:
          Fn::Equals:
            - '{{ commandRunningWebHookUrl }}'
            - ''
      Description:
        en: Send ecs command running report notification
        zh-cn: 发送运维通知
      Properties:
        NotifyType: WebHook
        WebHook:
          URI: '{{ commandRunningWebHookUrl }}'
          Headers:
            Content-Type: 'application/json; charset=utf-8'
          Content:
            msgtype: markdown
            markdown: 
              title: ECS Command Running Automation
              text: 
                Fn::Join:
                - ''
                - - |
                    ### ECS Command Running Automation

                    Start executing ecs command running for these accounts. Please pay attention to the status of your ecs instances.

                    | **Account ID** | **Regions** |
                    | --- | --- |
                  - Fn::Join:
                    - ''
                    - Fn::Jq:
                      - All
                      - '.[] | "|" + .accountId + "|" + (.regionIds | join(", ")) + "|\n"'
                      - '{{accountRoleAndRegions}}'
    - Name: executeCommandRunning
      Action: ACS::Template
      Description:
        en: Execute command running
        zh-cn: 运行命令
      Properties:
        TemplateName: '{{ templateName }}'
        Parameters:
          Fn::MergeMap:
            - '{{ templateParameters }}'
            - regionId:
                Fn::Select:
                  - regionId
                  - '{{ACS::TaskLoopItem}}'
            - OOSAssumeRole:
                Fn::Replace:
                  - $accountId:
                      Fn::Select:
                        - accountId
                        - '{{ACS::TaskLoopItem}}'
                  - '|$accountId/${var.oos_cross_account_assume_role}'
            - targets:
                Fn::Jq:
                  - First
                  - Fn::Replace:
                    - $regionId:
                        Fn::Select:
                          - regionId
                          - '{{ACS::TaskLoopItem}}'
                    - '(..|objects|select(has("RegionId"))).RegionId |= "$regionId"'
                  - Fn::Select:
                      - targets
                      - '{{ templateParameters }}'
      Loop:
        RateControl: '{{ rateControl }}'
        Items:
          Fn::First:
            Fn::Jq:
              - All
              - 'map(with_entries(select(.key != "regionIds")) + (.regionIds[] | {"regionId": .}))'
              - '{{ accountRoleAndRegions }}'
  EOF
}
