# AccessKey of the RAM user in management account
export ALICLOUD_ACCESS_KEY=""
export ALICLOUD_SECRET_KEY=""

# Create Member Accounts in Resource Directory
echo "\033[33m Start creating account.. \033[0m"
cd step/resource-create-account
terraform init
terraform plan --var-file ../../settings.tfvars -compact-warnings
terraform apply --auto-approve --var-file ../../settings.tfvars -compact-warnings
echo "\033[33m Account creation completed. \033[0m \n"


## Create CloudSSO user
echo "\033[33m Start creating CloudSSO user.. \033[0m"
cd ../cloudsso-create-user
terraform init
terraform plan --var-file ../../settings.tfvars -compact-warnings
terraform apply --auto-approve --var-file ../../settings.tfvars -compact-warnings

## Create RAM user and role
echo "\033[33m Start Createing RAM user and api access key.. \033[0m"
cd ../iam-create-user-api-key
terraform init
terraform plan --var-file ../../settings.tfvars -compact-warnings
terraform apply --auto-approve --var-file ../../settings.tfvars -compact-warnings
echo "\033[33m User and role creation completed. \033[0m \n"


## Authorize RAM user System policy
echo "\033[33m Start Authorize RAM user System policy.. \033[0m"
cd ../iam-authorize-user-role
terraform init
terraform plan --var-file ../../settings.tfvars -compact-warnings
terraform apply --auto-approve --var-file ../../settings.tfvars -compact-warnings -parallelism=2
echo "\033[33m User and role authorization completed. \033[0m \n"

## Create Resource Groups
echo "\033[33m Start Create Resource Groups.. \033[0m"
cd ../resource-create-group
terraform init
terraform plan --var-file ../../settings.tfvars -compact-warnings
terraform apply --auto-approve --var-file ../../settings.tfvars -compact-warnings
echo "\033[33m Create Resource Groups completed. \033[0m \n"


## Create TAG Policy And Tag create by
echo "\033[33m Start Create Tag Policy.. \033[0m"
cd ../resource-tag
python3 tag_service.py ../../settings.tfvars light_landingzone_region
terraform init
terraform plan --var-file ../../settings.tfvars -compact-warnings
terraform apply --auto-approve --var-file ../../settings.tfvars -compact-warnings
echo "\033[33m Create Create Tag Policy completed. \033[0m \n"

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

# Build dmz VPC
echo "\033[33m Start building vpc DMZ .. \033[0m"
cd ../network-build-vpc-dmz
terraform init
terraform plan --var-file ../../settings.tfvars -compact-warnings
terraform apply --auto-approve --var-file ../../settings.tfvars -compact-warnings -parallelism=2
echo "\033[33m DMZ VPC build completed. \033[0m \n"

# Create CEN & vpc attach
echo "\033[33m Start creating CEN and attaching VPC.. \033[0m"
cd ../network-attach-cen
python3 cen_service.py ../../settings.tfvars shared_service_account_id
terraform init
terraform plan --var-file ../../settings.tfvars -compact-warnings
terraform apply --auto-approve --var-file ../../settings.tfvars -compact-warnings -parallelism=2
echo "\033[33m CEN creation and VPC attachment completed. \033[0m \n"

# Configure routes
echo "\033[33m Start configuring route.. \033[0m"
cd ../network-config-route
terraform init
terraform plan --var-file ../../settings.tfvars -compact-warnings
terraform apply --auto-approve --var-file ../../settings.tfvars -compact-warnings
echo "\033[33m Route configuration completed. \033[0m \n"

# Deploy ECS and ALB
echo "\033[33m Start deploying ECS and ALB.. \033[0m"
cd ../application-deploy-ecs-alb
terraform init
terraform plan --var-file ../../settings.tfvars -compact-warnings
terraform apply --auto-approve --var-file ../../settings.tfvars -compact-warnings
echo "\033[33m ECS and ALB deployment completed. \033[0m \n"








