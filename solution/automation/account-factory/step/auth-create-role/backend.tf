# {state_key} should be replaced at each step
terraform {
  backend "oss" {
    bucket              = "xxx"
    prefix              = "xxx"
    key                 = "{state_key}"
    region              = "xxx"
    tablestore_endpoint = "xxx"
    tablestore_table    = "xxx"
    #    access_key = "xxx"
    #    secret_key = "xxx"
    endpoint            = "xxx"
  }
}