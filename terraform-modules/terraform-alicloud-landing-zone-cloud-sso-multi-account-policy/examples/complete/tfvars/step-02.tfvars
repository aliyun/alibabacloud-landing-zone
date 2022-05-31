# Step 2: Creating a list of cloud sso user group and access configuration
create_group                = true
create_access_configuration = true
display_name                = "AppNameDev"

# Remain the step 1 variables
#####################
# Cloud SSO Directory
#####################
create_directory            = true
directory_name              = "multi-account-module"
mfa_authentication_status   = "Enabled"
scim_synchronization_status = "Disabled"

#####################
# Resource Manager Folder
#####################
create_resource_manager_folder = true
folder_name                    = "multi-account-module"
