# {state_key} should be replaced at each step
terraform {
  backend "azurerm" {
    resource_group_name  = "xxx"
    storage_account_name = "xxx"
    container_name       = "xxx"
    key                  = "${state_key}"
  }
}

provider "azurerm" {
  features {}
}