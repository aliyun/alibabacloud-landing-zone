foundations = {
  master_uid = ""
  shared_services_uid = ""
  rd_folder_application_id = ""
  networking = {
    cen_instance_id = ""
    vswitches_shared_services = []
    vswitches_dmz = []
    vpc_production_id = ""
    vpc_non_production_id = ""
  }
}

applications_accounts = {
  app1 = {
    account_name = "app-ziyingtest-1"
    region = "cn-shanghai"
    networks = {
      vpc_production = {
        network_acl_enabled = false
        vswitches = [
          {
            vswitch_name = "vsw-app-1-a"
            cidr_block = "10.34.64.0/24"
            zone = "cn-shanghai-f"
          },
          {
            vswitch_name = "vsw-db-1-a"
            cidr_block = "10.34.70.0/24"
            zone = "cn-shanghai-f"
          },
          {
            vswitch_name = "vsw-app-1-b"
            cidr_block = "10.34.66.0/24"
            zone = "cn-shanghai-g"
          },
          {
            vswitch_name = "vsw-db-1-b"
            cidr_block = "10.34.71.0/24"
            zone = "cn-shanghai-g"
          },
          {
            vswitch_name = "vsw-app-1-c"
            cidr_block = "10.34.68.0/24"
            zone = "cn-shanghai-e"
          },
         
        ]
      }
      vpc_non_production = {
        network_acl_enabled = false
        vswitches = [
          {
            vswitch_name = "vsw-app-dev-1"
            cidr_block = "10.34.96.0/24"
            zone = "cn-shanghai-f"
          },
          {
            vswitch_name = "vsw-db-dev-1"
            cidr_block = "10.34.98.0/24"
            zone = "cn-shanghai-f"
          }
        ]
      }
    }
  }
  app2 = {
     account_name = "app-ziyingtest-2"
     region = "cn-shanghai"
     networks = {
      vpc_production = {
        network_acl_enabled = false
        vswitches = [
          {
            vswitch_name = "vsw-app-2-a"
            cidr_block = "10.34.65.0/24"
            zone = "cn-shanghai-f"
          },
          {
            vswitch_name = "vsw-db-2-a"
            cidr_block = "10.34.72.0/24"
            zone = "cn-shanghai-f"
          },
          {
            vswitch_name = "vsw-app-2-b"
            cidr_block = "10.34.67.0/24"
            zone = "cn-shanghai-g"
          },
          {
            vswitch_name = "vsw-db-2-b"
            cidr_block = "10.34.73.0/24"
            zone = "cn-shanghai-g"
          },
          {
            vswitch_name = "vsw-app-2-c"
            cidr_block = "10.34.69.0/24"
            zone = "cn-shanghai-e"
          }
        ]
      }
      vpc_non_production = {
        network_acl_enabled = false
        vswitches = [
          {
            vswitch_name = "vsw-app-dev-2"
            cidr_block = "10.34.97.0/24"
            zone = "cn-shanghai-f"
          },
          {
            vswitch_name = "vsw-db-dev-2"
            cidr_block = "10.34.99.0/24"
            zone = "cn-shanghai-f"
          }
        ]
      }
    }
  }
}