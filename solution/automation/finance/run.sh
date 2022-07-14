# AccessKey of the RAM user in management account
export ALICLOUD_ACCESS_KEY="LTAI5xxxxCQr"
export ALICLOUD_SECRET_KEY="9ebxxxx14o"

# Create Member Accounts in Resource Directory
echo "\033[33m Start creating account.. \033[0m"
cd step/resource-create-account
terraform init
terraform plan --var-file ../../settings.tfvars -compact-warnings
terraform apply --auto-approve --var-file ../../settings.tfvars -compact-warnings
echo "\033[33m Account creation completed. \033[0m \n"

# Create IDP
echo "\033[33m Start creating IDP.. \033[0m"
cd ../iam-create-idp
terraform init
terraform plan --var-file ../../settings.tfvars -compact-warnings
terraform apply --auto-approve --var-file ../../settings.tfvars -compact-warnings
echo "\033[33m IDP creation completed. \033[0m \n"

# Create RAM user and role
echo "\033[33m Start creating user and role.. \033[0m"
cd ../iam-create-user-role
terraform init
terraform plan --var-file ../../settings.tfvars -compact-warnings
terraform apply --auto-approve --var-file ../../settings.tfvars -compact-warnings
echo "\033[33m User and role creation completed. \033[0m \n"

# Authorize RAM user and role
echo "\033[33m Start authorizing RAM user and role.. \033[0m"
cd ../iam-authorize-user-role
terraform init
terraform plan --var-file ../../settings.tfvars -compact-warnings
terraform apply --auto-approve --var-file ../../settings.tfvars -compact-warnings -parallelism=2
echo "\033[33m User and role authorization completed. \033[0m \n"

# Enable compliance rules
echo "\033[33m Start enabling compliance rules.. \033[0m"
cd ../com-config-compliance-pack
terraform init
terraform plan --var-file ../../settings.tfvars -compact-warnings
terraform apply --auto-approve --var-file ../../settings.tfvars -compact-warnings
echo "\033[33m Enabling Compliance Rules Completed. \033[0m \n"

# Create VPC & VSwitch
echo "\033[33m Start creating vpc and vswitch.. \033[0m"
cd ../network-create-vpc
terraform init
terraform plan --var-file ../../settings.tfvars -compact-warnings
terraform apply --auto-approve --var-file ../../settings.tfvars -compact-warnings
echo "\033[33m VPC and VSwitch creation completed. \033[0m \n"

# Create CEN & vpc attach
echo "\033[33m Start creating CEN and attaching VPC.. \033[0m"
cd ../network-attach-cen
python3 cen_service.py ../../settings.tfvars shared_service_account_id
terraform init
terraform plan --var-file ../../settings.tfvars -compact-warnings
terraform apply --auto-approve --var-file ../../settings.tfvars -compact-warnings -parallelism=2
echo "\033[33m CEN creation and VPC attachment completed. \033[0m \n"

# Build dmz VPC
echo "\033[33m Start building DMZ VPC.. \033[0m"
cd ../network-build-dmz
terraform init
terraform plan --var-file ../../settings.tfvars -compact-warnings
terraform apply --auto-approve --var-file ../../settings.tfvars -compact-warnings -parallelism=2
echo "\033[33m DMZ VPC build completed. \033[0m \n"

# Unify public network egress
echo "\033[33m Start configuring unified egress route.. \033[0m"
cd ../network-config-unified-egress
terraform init
terraform plan --var-file ../../settings.tfvars -compact-warnings
terraform apply --auto-approve --var-file ../../settings.tfvars -compact-warnings
echo "\033[33m Unified egress route configuration completed. \033[0m \n"

# Deploy ECS and ALB
echo "\033[33m Start deploying ECS and ALB.. \033[0m"
cd ../network-deploy-ecs-alb
terraform init
terraform plan --var-file ../../settings.tfvars -compact-warnings
terraform apply --auto-approve --var-file ../../settings.tfvars -compact-warnings
echo "\033[33m ECS and ALB deployment completed. \033[0m \n"

## The prepaid version of the security product cannot be released in advance, so use it with caution
## Subscribe WAF
#echo "\033[33m Start subscribing to WAF.. \033[0m"
#cd ../sec-subscribe-waf
#terraform init
#terraform plan --var-file ../../settings.tfvars -compact-warnings
#terraform apply --auto-approve --var-file ../../settings.tfvars -compact-warnings
#echo "\033[33m WAF deployment completed. \033[0m \n"
#
## Subscribe Anti-DDoS
#echo "\033[33m Start subscribing to Anti-DDoS.. \033[0m"
#cd ../sec-subscribe-anti-ddos
#terraform init
#terraform plan --var-file ../../settings.tfvars -compact-warnings
#terraform apply --auto-approve --var-file ../../settings.tfvars -compact-warnings
#echo "\033[33m Anti-DDoS deployment completed. \033[0m \n"
#
## Subscribe cloud firewall
#echo "\033[33m Start subscribing to Cloud Firewall.. \033[0m"
#cd ../sec-subscribe-cfw
#terraform init
#terraform plan --var-file ../../settings.tfvars -compact-warnings
#terraform apply --auto-approve --var-file ../../settings.tfvars -compact-warnings
#echo "\033[33m Cloud Firewall deployment completed. \033[0m \n"
