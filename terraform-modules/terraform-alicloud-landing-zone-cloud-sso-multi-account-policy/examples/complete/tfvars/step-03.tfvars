# Step 3: Creating a RD account and assign the policy for it
create_resource_manager_account = true
assign_access_configuration     = true

# Remain the step 2 variables
create_group                = true
create_access_configuration = true
display_name                = "AppNameDev"

# Remain the step 1 variables
create_directory            = true
directory_name              = "multi-account-module"
mfa_authentication_status   = "Enabled"
scim_synchronization_status = "Disabled"

create_resource_manager_folder = true
folder_name                    = "multi-account-module"
