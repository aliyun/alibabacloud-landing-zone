# 创建成员账号ram user的权限策略
variable "roles"{
    type = list(object({
    name = string
  }))
  default=[
    {
      name = "AliyunCSManagedVKRole"
    },
    {
      name = "AliyunCSDefaultRole"
    },
    {
      name = "AliyunCSManagedKubernetesRole"
    },
    {
      name = "AliyunCSManagedLogRole"
    },
    {
      name = "AliyunCSManagedCmsRole"
    },
    {
      name = "AliyunCSManagedCsiRole"
    },
    {
      name = "AliyunCSKubernetesAuditRole"
    },
    {
      name = "AliyunCSManagedNetworkRole"
    },
    {
      name = "AliyunCSManagedArmsRole"
    },
    {
      name = "AliyunCSServerlessKubernetesRole"
    }
  ]
}

variable "rolesAttachPolicy"{
    type = list(object({
    name = string
    policy = string
  }))
  default=[
    {
      name = "AliyunCSManagedVKRole"
      policy = "AliyunCSManagedVKRolePolicy"
    },
    {
      name = "AliyunCSDefaultRole"
      policy = "AliyunCSDefaultRolePolicy1"
    },
    {
      name = "AliyunCSDefaultRole"
      policy = "AliyunCSDefaultRolePolicy2"
    },
    {
      name = "AliyunCSDefaultRole"
      policy = "AliyunCSDefaultRolePolicy3"
    },
    {
      name = "AliyunCSDefaultRole"
      policy = "AliyunCSDefaultRolePolicy4"
    },
    {
      name = "AliyunCSManagedKubernetesRole"
      policy = "AliyunCSManagedKubernetesRolePolicy"
    },
    {
      name = "AliyunCSManagedLogRole"
      policy = "AliyunCSManagedLogRolePolicy"
    },
    {
      name = "AliyunCSManagedCmsRole"
      policy = "AliyunCSManagedCmsRolePolicy"
    },
    {
      name = "AliyunCSManagedCsiRole"
      policy = "AliyunCSManagedCsiRolePolicy"
    },
    {
      name = "AliyunCSKubernetesAuditRole"
      policy = "AliyunCSKubernetesAuditRolePolicy"
    },
    {
      name = "AliyunCSManagedNetworkRole"
      policy = "AliyunCSManagedNetworkRolePolicy"
    },
    {
      name = "AliyunCSManagedArmsRole"
      policy = "AliyunCSManagedArmsRolePolicy"
    },
    {
      name = "AliyunCSServerlessKubernetesRole"
      policy = "AliyunCSServerlessKubernetesRolePolicy2"
    },
    {
      name = "AliyunCSServerlessKubernetesRole"
      policy = "AliyunCSServerlessKubernetesRolePolicy1"
    }
  ]
}


variable "policys"{
    type = list(object({
    name = string
    document = string
    description = string
  }))
  default=[
    {
      name = "AliyunCSManagedVKRolePolicy"
      document = <<EOF
                        {
                        "Version": "1",
                        "Statement": [
                            {
                                "Action": [
                                    "vpc:DescribeVSwitches",
                                    "vpc:DescribeVpcs",
                                    "vpc:AssociateEipAddress",
                                    "vpc:DescribeEipAddresses",
                                    "vpc:AllocateEipAddress",
                                    "vpc:ReleaseEipAddress"
                                ],
                                "Resource": "*",
                                "Effect": "Allow"
                            },
                            {
                                "Action": [
                                    "ecs:DescribeSecurityGroups",
                                    "ecs:CreateNetworkInterface",
                                    "ecs:CreateNetworkInterfacePermission",
                                    "ecs:DescribeNetworkInterfaces",
                                    "ecs:AttachNetworkInterface",
                                    "ecs:DetachNetworkInterface",
                                    "ecs:DeleteNetworkInterface",
                                    "ecs:DeleteNetworkInterfacePermission"
                                ],
                                "Resource": "*",
                                "Effect": "Allow"
                            },
                            {
                                "Action": [
                                    "pvtz:AddZone",
                                    "pvtz:DeleteZone",
                                    "pvtz:DescribeZones",
                                    "pvtz:DescribeZoneInfo",
                                    "pvtz:BindZoneVpc",
                                    "pvtz:AddZoneRecord",
                                    "pvtz:DeleteZoneRecord",
                                    "pvtz:DeleteZoneRecordsByRR",
                                    "pvtz:DescribeZoneRecordsByRR",
                                    "pvtz:DescribeZoneRecords"
                                ],
                                "Resource": "*",
                                "Effect": "Allow"
                            },
                            {
                                "Action": [
                                    "eci:CreateContainerGroup",
                                    "eci:DeleteContainerGroup",
                                    "eci:DescribeContainerGroups",
                                    "eci:DescribeContainerLog",
                                    "eci:UpdateContainerGroup",
                                    "eci:UpdateContainerGroupByTemplate",
                                    "eci:CreateContainerGroupFromTemplate",
                                    "eci:RestartContainerGroup",
                                    "eci:ExportContainerGroupTemplate",
                                    "eci:DescribeContainerGroupMetric",
                                    "eci:DescribeMultiContainerGroupMetric",
                                    "eci:ExecContainerCommand",
                                    "eci:CreateImageCache",
                                    "eci:DescribeImageCaches",
                                    "eci:DeleteImageCache"
                                ],
                                "Resource": "*",
                                "Effect": "Allow"
                            }
                        ]
                    }
                    EOF
      description = ""
    },
    {
      name = "AliyunCSManagedKubernetesRolePolicy"
      document = <<EOF
                        {
                        "Version": "1",
                        "Statement": [
                            {
                                "Action": [
                                    "ecs:Describe*",
                                    "ecs:CreateRouteEntry",
                                    "ecs:DeleteRouteEntry",
                                    "ecs:CreateNetworkInterface",
                                    "ecs:DeleteNetworkInterface",
                                    "ecs:CreateNetworkInterfacePermission",
                                    "ecs:DeleteNetworkInterfacePermission",
                                    "ecs:ModifyInstanceAttribute",
                                    "ecs:AttachKeyPair",
                                    "ecs:StopInstance",
                                    "ecs:StartInstance",
                                    "ecs:ReplaceSystemDisk"
                                ],
                                "Resource": [
                                    "*"
                                ],
                                "Effect": "Allow"
                            },
                            {
                                "Action": [
                                    "slb:Describe*",
                                    "slb:CreateLoadBalancer",
                                    "slb:DeleteLoadBalancer",
                                    "slb:ModifyLoadBalancerInternetSpec",
                                    "slb:RemoveBackendServers",
                                    "slb:AddBackendServers",
                                    "slb:RemoveTags",
                                    "slb:AddTags",
                                    "slb:StopLoadBalancerListener",
                                    "slb:StartLoadBalancerListener",
                                    "slb:SetLoadBalancerHTTPListenerAttribute",
                                    "slb:SetLoadBalancerHTTPSListenerAttribute",
                                    "slb:SetLoadBalancerTCPListenerAttribute",
                                    "slb:SetLoadBalancerUDPListenerAttribute",
                                    "slb:CreateLoadBalancerHTTPSListener",
                                    "slb:CreateLoadBalancerHTTPListener",
                                    "slb:CreateLoadBalancerTCPListener",
                                    "slb:CreateLoadBalancerUDPListener",
                                    "slb:DeleteLoadBalancerListener",
                                    "slb:CreateVServerGroup",
                                    "slb:DescribeVServerGroups",
                                    "slb:DeleteVServerGroup",
                                    "slb:SetVServerGroupAttribute",
                                    "slb:DescribeVServerGroupAttribute",
                                    "slb:ModifyVServerGroupBackendServers",
                                    "slb:AddVServerGroupBackendServers",
                                    "slb:ModifyLoadBalancerInstanceSpec",
                                    "slb:ModifyLoadBalancerInternetSpec",
                                    "slb:SetLoadBalancerModificationProtection",
                                    "slb:SetLoadBalancerDeleteProtection",
                                    "slb:SetLoadBalancerName",
                                    "slb:RemoveVServerGroupBackendServers"
                                ],
                                "Resource": [
                                    "*"
                                ],
                                "Effect": "Allow"
                            },
                            {
                                "Action": [
                                    "vpc:Describe*",
                                    "vpc:DeleteRouteEntry",
                                    "vpc:CreateRouteEntry"
                                ],
                                "Resource": [
                                    "*"
                                ],
                                "Effect": "Allow"
                            },
                            {
                                "Action": [
                                    "cr:Get*",
                                    "cr:List*",
                                    "cr:PullRepository"
                                ],
                                "Resource": "*",
                                "Effect": "Allow"
                            }
                        ]
                    }
                    EOF
      description = ""
    },

    {
      name = "AliyunCSManagedLogRolePolicy"
      document = <<EOF
                       {
                        "Version": "1",
                        "Statement": [
                            {
                                "Action": [
                                    "log:CreateProject",
                                    "log:GetProject",
                                    "log:DeleteProject",
                                    "log:CreateLogStore",
                                    "log:GetLogStore",
                                    "log:UpdateLogStore",
                                    "log:DeleteLogStore",
                                    "log:CreateConfig",
                                    "log:UpdateConfig",
                                    "log:GetConfig",
                                    "log:DeleteConfig",
                                    "log:CreateMachineGroup",
                                    "log:UpdateMachineGroup",
                                    "log:GetMachineGroup",
                                    "log:DeleteMachineGroup",
                                    "log:ApplyConfigToGroup",
                                    "log:GetAppliedMachineGroups",
                                    "log:GetAppliedConfigs",
                                    "log:RemoveConfigFromMachineGroup",
                                    "log:CreateIndex",
                                    "log:GetIndex",
                                    "log:UpdateIndex",
                                    "log:DeleteIndex",
                                    "log:CreateSavedSearch",
                                    "log:GetSavedSearch",
                                    "log:UpdateSavedSearch",
                                    "log:DeleteSavedSearch",
                                    "log:CreateDashboard",
                                    "log:GetDashboard",
                                    "log:UpdateDashboard",
                                    "log:DeleteDashboard",
                                    "log:CreateJob",
                                    "log:GetJob",
                                    "log:DeleteJob",
                                    "log:UpdateJob",
                                    "log:PostLogStoreLogs",
                                    "log:CreateSortedSubStore",
                                    "log:GetSortedSubStore",
                                    "log:ListSortedSubStore",
                                    "log:UpdateSortedSubStore",
                                    "log:DeleteSortedSubStore",
                                    "log:CreateApp",
                                    "log:UpdateApp",
                                    "log:GetApp",
                                    "log:DeleteApp",
                                    "cs:DescribeTemplates",
                                    "cs:DescribeTemplateAttribute"
                                ],
                                "Resource": [
                                    "*"
                                ],
                                "Effect": "Allow"
                            }
                            ]
                        }
                        EOF
      description = ""
    },

    {
      name = "AliyunCSManagedCmsRolePolicy"
      document = <<EOF
                       {
                        "Version": "1",
                        "Statement": [
                            {
                                "Action": [
                                    "cms:DescribeMonitorGroups",
                                    "cms:DescribeMonitorGroupInstances",
                                    "cms:CreateMonitorGroup",
                                    "cms:DeleteMonitorGroup",
                                    "cms:ModifyMonitorGroupInstances",
                                    "cms:CreateMonitorGroupInstances",
                                    "cms:DeleteMonitorGroupInstances",
                                    "cms:TaskConfigCreate",
                                    "cms:TaskConfigList",
                                    "cms:DescribeMetricList",
                                    "cs:DescribeMonitorToken",
                                    "ahas:GetSentinelAppSumMetric",
                                    "log:GetLogStoreLogs",
                                    "slb:DescribeMetricList",
                                    "sls:GetLogs",
                                    "sls:PutLogs"
                                ],
                                "Resource": [
                                    "*"
                                ],
                                "Effect": "Allow"
                            }
                        ]
                    }
                    EOF
      description = ""
    },

    {
      name = "AliyunCSManagedCsiRolePolicy"
      document = <<EOF
                     {
                          "Version": "1",
                          "Statement": [
                              {
                                  "Action": [
                                      "ecs:AttachDisk",
                                      "ecs:DetachDisk",
                                      "ecs:DescribeDisks",
                                      "ecs:CreateDisk",
                                      "ecs:ResizeDisk",
                                      "ecs:CreateSnapshot",
                                      "ecs:DeleteSnapshot",
                                      "ecs:CreateAutoSnapshotPolicy",
                                      "ecs:ApplyAutoSnapshotPolicy",
                                      "ecs:CancelAutoSnapshotPolicy",
                                      "ecs:DeleteAutoSnapshotPolicy",
                                      "ecs:DescribeAutoSnapshotPolicyEX",
                                      "ecs:ModifyAutoSnapshotPolicyEx",
                                      "ecs:AddTags",
                                      "ecs:DescribeTags",
                                      "ecs:DescribeSnapshots",
                                      "ecs:ListTagResources",
                                      "ecs:TagResources",
                                      "ecs:UntagResources",
                                      "ecs:ModifyDiskSpec",
                                      "ecs:CreateSnapshot",
                                      "ecs:DeleteDisk",
                                      "ecs:DescribeInstanceAttribute",
                                      "ecs:DescribeInstances"
                                  ],
                                  "Resource": [
                                      "*"
                                  ],
                                  "Effect": "Allow"
                              },
                              {
                                  "Action": [
                                      "nas:DescribeFileSystems",
                                      "nas:DescribeMountTargets",
                                      "nas:AddTags",
                                      "nas:DescribeTags",
                                      "nas:RemoveTags",
                                      "nas:CreateFileSystem",
                                      "nas:DeleteFileSystem",
                                      "nas:DescribeFileSystems",
                                      "nas:ModifyFileSystem",
                                      "nas:CreateMountTarget",
                                      "nas:DeleteMountTarget",
                                      "nas:DescribeMountTargets",
                                      "nas:ModifyMountTarget"
                                  ],
                                  "Resource": [
                                      "*"
                                  ],
                                  "Effect": "Allow"
                              }
                          ]
                      }
                      EOF
      description = ""
    },
    {
      name = "AliyunCSKubernetesAuditRolePolicy"
      document = <<EOF
                        {
                          "Version": "1",
                          "Statement": [
                              {
                                  "Action": [
                                      "log:CreateProject",
                                      "log:GetProject",
                                      "log:DeleteProject",
                                      "log:CreateLogStore",
                                      "log:GetLogStore",
                                      "log:UpdateLogStore",
                                      "log:DeleteLogStore",
                                      "log:CreateConfig",
                                      "log:UpdateConfig",
                                      "log:GetConfig",
                                      "log:DeleteConfig",
                                      "log:CreateMachineGroup",
                                      "log:UpdateMachineGroup",
                                      "log:GetMachineGroup",
                                      "log:DeleteMachineGroup",
                                      "log:ApplyConfigToGroup",
                                      "log:GetAppliedMachineGroups",
                                      "log:GetAppliedConfigs",
                                      "log:RemoveConfigFromMachineGroup",
                                      "log:CreateIndex",
                                      "log:GetIndex",
                                      "log:UpdateIndex",
                                      "log:DeleteIndex",
                                      "log:CreateSavedSearch",
                                      "log:GetSavedSearch",
                                      "log:UpdateSavedSearch",
                                      "log:DeleteSavedSearch",
                                      "log:CreateDashboard",
                                      "log:GetDashboard",
                                      "log:UpdateDashboard",
                                      "log:DeleteDashboard",
                                      "log:CreateJob",
                                      "log:GetJob",
                                      "log:DeleteJob",
                                      "log:UpdateJob",
                                      "log:PostLogStoreLogs"
                                  ],
                                  "Resource": "*",
                                  "Effect": "Allow"
                              }
                          ]
                      }
                      EOF
      description = ""
    },
    {
      name = "AliyunCSManagedNetworkRolePolicy"
      document = <<EOF
                       {
                        "Version": "1",
                        "Statement": [
                            {
                                "Action": [
                                    "ecs:CreateNetworkInterface",
                                    "ecs:DescribeNetworkInterfaces",
                                    "ecs:AttachNetworkInterface",
                                    "ecs:DetachNetworkInterface",
                                    "ecs:DeleteNetworkInterface",
                                    "ecs:DescribeInstanceAttribute",
                                    "ecs:AssignPrivateIpAddresses",
                                    "ecs:UnassignPrivateIpAddresses",
                                    "ecs:DescribeInstances"
                                ],
                                "Resource": [
                                    "*"
                                ],
                                "Effect": "Allow"
                            },
                            {
                                "Action": [
                                    "vpc:DescribeVSwitches"
                                ],
                                "Resource": [
                                    "*"
                                ],
                                "Effect": "Allow"
                            }
                        ]
                    }
                    EOF
      description = ""
    },

    {
      name = "AliyunCSManagedArmsRolePolicy"
      document = <<EOF
                       {
                        "Version": "1",
                        "Statement": [
                            {
                                "Action": [
                                    "arms:CreateApp",
                                    "arms:DeleteApp",
                                    "arms:ConfigAgentLabel",
                                    "arms:GetAssumeRoleCredentials",
                                    "arms:CreateProm",
                                    "arms:SearchEvents",
                                    "arms:SearchAlarmHistories",
                                    "arms:SearchAlertRules",
                                    "arms:GetAlertRules",
                                    "arms:CreateAlertRules",
                                    "arms:UpdateAlertRules",
                                    "arms:StartAlertRule",
                                    "arms:StopAlertRule",
                                    "arms:CreateContact",
                                    "arms:SearchContact",
                                    "arms:UpdateContact",
                                    "arms:CreateContactGroup",
                                    "arms:SearchContactGroup",
                                    "arms:UpdateContactGroup"
                                ],
                                "Resource": [
                                    "*"
                                ],
                                "Effect": "Allow"
                            }
                        ]
                    }
                    EOF
      description = ""
    },

    {
      name = "AliyunCSServerlessKubernetesRolePolicy1"
      document = <<EOF
                       {
            "Version": "1",
            "Statement": [
                {
                    "Action": [
                        "vpc:DescribeVSwitches",
                        "vpc:DescribeVpcs",
                        "vpc:AssociateEipAddress",
                        "vpc:DescribeEipAddresses",
                        "vpc:AllocateEipAddress",
                        "vpc:ReleaseEipAddress",
                        "vpc:AddCommonBandwidthPackageIp",
                        "vpc:RemoveCommonBandwidthPackageIp"
                    ],
                    "Resource": "*",
                    "Effect": "Allow"
                },
                {
                    "Action": [
                        "ecs:DescribeSecurityGroups",
                        "ecs:CreateNetworkInterface",
                        "ecs:CreateNetworkInterfacePermission",
                        "ecs:DescribeNetworkInterfaces",
                        "ecs:AttachNetworkInterface",
                        "ecs:DetachNetworkInterface",
                        "ecs:DeleteNetworkInterface",
                        "ecs:DeleteNetworkInterfacePermission"
                    ],
                    "Resource": "*",
                    "Effect": "Allow"
                },
                {
                    "Action": [
                        "slb:Describe*",
                        "slb:CreateLoadBalancer",
                        "slb:DeleteLoadBalancer",
                        "slb:RemoveBackendServers",
                        "slb:StartLoadBalancerListener",
                        "slb:StopLoadBalancerListener",
                        "slb:DeleteLoadBalancerListener",
                        "slb:CreateLoadBalancerTCPListener",
                        "slb:AddBackendServers*",
                        "slb:UploadServerCertificate",
                        "slb:CreateLoadBalancerHTTPListener",
                        "slb:CreateLoadBalancerHTTPSListener",
                        "slb:CreateLoadBalancerUDPListener",
                        "slb:ModifyLoadBalancerInternetSpec",
                        "slb:CreateRules",
                        "slb:DeleteRules",
                        "slb:SetRule",
                        "slb:CreateVServerGroup",
                        "slb:SetVServerGroupAttribute",
                        "slb:AddVServerGroupBackendServers",
                        "slb:RemoveVServerGroupBackendServers",
                        "slb:ModifyVServerGroupBackendServers",
                        "slb:DeleteVServerGroup",
                        "slb:SetLoadBalancerTCPListenerAttribute",
                        "slb:SetLoadBalancerHTTPListenerAttribute",
                        "slb:SetLoadBalancerHTTPSListenerAttribute",
                        "slb:AddTags"
                    ],
                    "Resource": "*",
                    "Effect": "Allow"
                }
            ]
        }
                    EOF
      description = ""
    },

    {
      name = "AliyunCSServerlessKubernetesRolePolicy2"
      document = <<EOF
                       {
            "Version": "1",
            "Statement": [
                {
                    "Action": [
                        "pvtz:AddZone",
                        "pvtz:DeleteZone",
                        "pvtz:DescribeZones",
                        "pvtz:DescribeZoneInfo",
                        "pvtz:BindZoneVpc",
                        "pvtz:AddZoneRecord",
                        "pvtz:DeleteZoneRecord",
                        "pvtz:DeleteZoneRecordsByRR",
                        "pvtz:DescribeZoneRecordsByRR",
                        "pvtz:DescribeZoneRecords"
                    ],
                    "Resource": "*",
                    "Effect": "Allow"
                },
                {
                    "Action": [
                        "cr:Get*",
                        "cr:List*",
                        "cr:PullRepository"
                    ],
                    "Resource": "*",
                    "Effect": "Allow"
                },
                {
                    "Action": [
                        "eci:CreateContainerGroup",
                        "eci:DeleteContainerGroup",
                        "eci:DescribeContainerGroups",
                        "eci:DescribeContainerLog",
                        "eci:UpdateContainerGroup",
                        "eci:UpdateContainerGroupByTemplate",
                        "eci:CreateContainerGroupFromTemplate",
                        "eci:RestartContainerGroup",
                        "eci:ExportContainerGroupTemplate",
                        "eci:DescribeContainerGroupMetric",
                        "eci:DescribeMultiContainerGroupMetric",
                        "eci:ExecContainerCommand",
                        "eci:CreateImageCache",
                        "eci:DescribeImageCaches",
                        "eci:DeleteImageCache"
                    ],
                    "Resource": "*",
                    "Effect": "Allow"
                },
                {
                    "Action": "ram:PassRole",
                    "Resource": "*",
                    "Effect": "Allow"
                },
                {
                    "Action": [
                        "oss:GetObject",
                        "oss:GetObjectMeta"
                    ],
                    "Resource": "*",
                    "Effect": "Allow"
                },
                {
                    "Action": [
                        "fc:CreateService",
                        "fc:ListServices",
                        "fc:GetService",
                        "fc:UpdateService",
                        "fc:DeleteService",
                        "fc:CreateFunction",
                        "fc:ListFunctions",
                        "fc:GetFunction",
                        "fc:GetFunctionCode",
                        "fc:UpdateFunction",
                        "fc:DeleteFunction",
                        "fc:CreateTrigger",
                        "fc:ListTriggers",
                        "fc:GetTrigger",
                        "fc:UpdateTrigger",
                        "fc:DeleteTrigger",
                        "fc:PublishServiceVersion",
                        "fc:ListServiceVersions",
                        "fc:DeleteServiceVersion",
                        "fc:CreateAlias",
                        "fc:ListAliases",
                        "fc:GetAlias",
                        "fc:UpdateAlias",
                        "fc:DeleteAlias"
                    ],
                    "Resource": "acs:fc:*:*:services/*",
                    "Effect": "Allow"
                }
            ]
        }
                    EOF
      description = ""
    },

    {
      name = "AliyunCSDefaultRolePolicy1"
      document = <<EOF
                      {
    "Version": "1",
    "Statement": [
        {
            "Action": [
                "ram:Get*",
                "ram:List*"
            ],
            "Resource": [
                "*"
            ],
            "Effect": "Allow"
        },
        {
            "Action": [
                "ram:DetachPolicyFromRole",
                "ram:AttachPolicyToRole",
                "ram:DeletePolicy",
                "ram:DeletePolicyVersion",
                "ram:DeleteRole"
            ],
            "Resource": [
                "acs:ram:*:*:role/KubernetesMasterRole-*",
                "acs:ram:*:*:role/KubernetesWorkerRole-*",
                "acs:ram:*:*:policy/k8sMasterRolePolicy-*",
                "acs:ram:*:*:policy/k8sWorkerRolePolicy-*"
            ],
            "Effect": "Allow"
        },
        {
            "Action": [
                "ram:CreateRole",
                "ram:CreatePolicy"
            ],
            "Resource": [
                "acs:ram:*:*:role/*",
                "acs:ram:*:*:policy/*"
            ],
            "Effect": "Allow"
        },
        {
            "Action": [
                "cms:CreateMyGroups",
                "cms:AddMyGroupInstances",
                "cms:DeleteMyGroupInstances",
                "cms:DeleteMyGroups",
                "cms:GetMyGroups",
                "cms:ListMyGroups",
                "cms:UpdateMyGroupInstances",
                "cms:UpdateMyGroups",
                "cms:TaskConfigCreate",
                "cms:TaskConfigList"
            ],
            "Resource": "*",
            "Effect": "Allow"
        },
        {
            "Action": [
                "ess:CreateLifecycleHook",
                "ess:DescribeLifecycleHooks",
                "ess:ModifyLifecycleHook",
                "ess:DeleteLifecycleHook"
            ],
            "Resource": "*",
            "Effect": "Allow"
        },
        {
            "Action": [
                "ens:Describe*",
                "ens:CreateInstance",
                "ens:StartInstance",
                "ens:StopInstance",
                "ens:ReleasePrePaidInstance"
            ],
            "Resource": "*",
            "Effect": "Allow"
        },
        {
            "Action": "ram:CreateServiceLinkedRole",
            "Resource": "*",
            "Effect": "Allow",
            "Condition": {
                "StringEquals": {
                    "ram:ServiceName": [
                        "ess.aliyuncs.com",
                        "nat.aliyuncs.com"
                    ]
                }
            }
        }
    ]
}
                    EOF
      description = ""
    },

    {
      name = "AliyunCSDefaultRolePolicy4"
      document = <<EOF
                      {
    "Version": "1",
    "Statement": [
        {
            "Action": [
                "dns:Describe*",
                "dns:AddDomainRecord"
            ],
            "Resource": [
                "*"
            ],
            "Effect": "Allow"
        },
        {
            "Action": [
                "rds:Describe*",
                "rds:ModifySecurityIps"
            ],
            "Resource": [
                "*"
            ],
            "Effect": "Allow"
        },
        {
            "Action": [
                "ros:Describe*",
                "ros:WaitConditions",
                "ros:AbandonStack",
                "ros:DeleteStack",
                "ros:CreateStack",
                "ros:UpdateStack",
                "ros:ValidateTemplate",
                "ros:DoActions",
                "ros:InquiryStack",
                "ros:SetDeletionProtection",
                "ros:PreviewStack"
            ],
            "Resource": [
                "*"
            ],
            "Effect": "Allow"
        },
        {
            "Action": "ram:PassRole",
            "Resource": "*",
            "Effect": "Allow"
        },
        {
            "Action": [
                "ess:Describe*",
                "ess:CreateScalingConfiguration",
                "ess:EnableScalingGroup",
                "ess:ExitStandby",
                "ess:DetachDBInstances",
                "ess:DetachLoadBalancers",
                "ess:AttachInstances",
                "ess:DeleteScalingConfiguration",
                "ess:AttachLoadBalancers",
                "ess:DetachInstances",
                "ess:ModifyScalingRule",
                "ess:RemoveInstances",
                "ess:ModifyScalingGroup",
                "ess:AttachDBInstances",
                "ess:CreateScalingRule",
                "ess:DeleteScalingRule",
                "ess:ExecuteScalingRule",
                "ess:SetInstancesProtection",
                "ess:ModifyNotificationConfiguration",
                "ess:CreateNotificationConfiguration",
                "ess:EnterStandby",
                "ess:DeleteScalingGroup",
                "ess:CreateScalingGroup",
                "ess:DisableScalingGroup",
                "ess:DeleteNotificationConfiguration",
                "ess:ModifyScalingConfiguration",
                "ess:SetGroupDeletionProtection"
            ],
            "Resource": "*",
            "Effect": "Allow"
        }

    ]
}
                    EOF
      description = ""
    },
    {
      name = "AliyunCSDefaultRolePolicy2"
      document = <<EOF
                     {
    "Version": "1",
    "Statement": [
        {
            "Action": [
                "ecs:RunInstances",
                "ecs:RenewInstance",
                "ecs:Create*",
                "ecs:AllocatePublicIpAddress",
                "ecs:AllocateEipAddress",
                "ecs:Delete*",
                "ecs:StartInstance",
                "ecs:StopInstance",
                "ecs:RebootInstance",
                "ecs:Describe*",
                "ecs:AuthorizeSecurityGroup",
                "ecs:RevokeSecurityGroup",
                "ecs:AuthorizeSecurityGroupEgress",
                "ecs:AttachDisk",
                "ecs:DetachDisk",
                "ecs:WaitFor*",
                "ecs:AddTags",
                "ecs:ReplaceSystemDisk",
                "ecs:ModifyInstanceAttribute",
                "ecs:JoinSecurityGroup",
                "ecs:LeaveSecurityGroup",
                "ecs:UnassociateEipAddress",
                "ecs:ReleaseEipAddress",
                "ecs:CreateKeyPair",
                "ecs:ImportKeyPair",
                "ecs:AttachKeyPair",
                "ecs:DetachKeyPair",
                "ecs:DeleteKeyPairs",
                "ecs:AttachInstanceRamRole",
                "ecs:DetachInstanceRamRole",
                "ecs:AllocateDedicatedHosts",
                "ecs:CreateOrder",
                "ecs:DeleteInstance",
                "ecs:CreateDisk",
                "ecs:Createvpc",
                "ecs:Deletevpc",
                "ecs:DeleteVSwitch",
                "ecs:ResetDisk",
                "ecs:DeleteSnapshot",
                "ecs:AllocatePublicIpAddress",
                "ecs:CreateVSwitch",
                "ecs:DeleteSecurityGroup",
                "ecs:CreateImage",
                "ecs:RemoveTags",
                "ecs:ReleaseDedicatedHost",
                "ecs:CreateInstance",
                "ecs:RevokeSecurityGroupEgress",
                "ecs:DeleteDisk",
                "ecs:StopInstance",
                "ecs:CreateSecurityGroup",
                "ecs:DeleteImage",
                "ecs:ModifyInstanceSpec",
                "ecs:CreateSnapshot",
                "ecs:CreateCommand",
                "ecs:InvokeCommand",
                "ecs:StopInvocation",
                "ecs:DeleteCommand",
                "ecs:RunCommand",
                "ecs:DescribeInvocationResults",
                "ecs:ModifyCommand"
            ],
            "Resource": "*",
            "Effect": "Allow"
        }
    ]
}
                    EOF
      description = ""
    },
    {
    name = "AliyunCSDefaultRolePolicy3"
    document = <<EOF
                        {
    "Version": "1",
    "Statement": [
        {
            "Action": [
                "vpc:Describe*",
                "vpc:AllocateEipAddress",
                "vpc:AssociateEipAddress",
                "vpc:UnassociateEipAddress",
                "vpc:ReleaseEipAddress",
                "vpc:CreateRouteEntry",
                "vpc:DeleteRouteEntry",
                "vpc:CreateVSwitch",
                "vpc:DeleteVSwitch",
                "vpc:CreateVpc",
                "vpc:DeleteVpc",
                "vpc:CreateNatGateway",
                "vpc:DeleteNatGateway",
                "vpc:CreateSnatEntry",
                "vpc:DeleteSnatEntry",
                "vpc:ModifyEipAddressAttribute",
                "vpc:CreateForwardEntry",
                "vpc:DeleteBandwidthPackage",
                "vpc:CreateBandwidthPackage",
                "vpc:DeleteForwardEntry",
                "vpc:TagResources",
                "vpc:DeletionProtection"
            ],
            "Resource": "*",
            "Effect": "Allow"
        },
        {
            "Action": [
                "slb:Describe*",
                "slb:CreateLoadBalancer",
                "slb:DeleteLoadBalancer",
                "slb:RemoveBackendServers",
                "slb:StartLoadBalancerListener",
                "slb:StopLoadBalancerListener",
                "slb:CreateLoadBalancerTCPListener",
                "slb:AddBackendServers*",
                "slb:CreateVServerGroup",
                "slb:CreateLoadBalancerHTTPSListener",
                "slb:CreateLoadBalancerUDPListener",
                "slb:ModifyLoadBalancerInternetSpec",
                "slb:SetBackendServers",
                "slb:AddVServerGroupBackendServers",
                "slb:DeleteVServerGroup",
                "slb:ModifyVServerGroupBackendServers",
                "slb:CreateLoadBalancerHTTPListener",
                "slb:RemoveVServerGroupBackendServers",
                "slb:DeleteLoadBalancerListener",
                "slb:AddTags",
                "slb:RemoveTags",
                "slb:SetLoadBalancerDeleteProtection"
            ],
            "Resource": [
                "*"
            ],
            "Effect": "Allow"
        }
    ]
}
                    EOF
    description = ""
    }
  ]
}
