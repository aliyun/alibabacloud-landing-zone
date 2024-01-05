variable "region" {
  type        = string
  default     = ""
  description = "The ID of the deployment region"
}

variable "imageFamily" {
  type        = string
  default     = "GoldenImage"
  description = "The image family of the golden image to aggregate a group of golden images for the same purpose. You get to override this later when you trigger automation workflow."
}

variable "imageOSAndVersion" {
  type        = string
  default     = "OperatingSystemName-OperatingSystemVersion"
  description = "Operating system name and OS version. You get to override this later when you trigger automation workflow."
}

variable "imageVersion" {
  type        = string
  default     = "1"
  description = "Build-Version corresponding to your golden image. Note - This is just a default value, you get to override this later when you trigger automation workflow."
}

variable "cidrVpc" {
  type        = string
  default     = "10.0.0.0/8"
  description = "An available CIDR block for creating a new VPC. The size of the VPC should be big enough to hold instances of all your golden AMIs at a time"
  validation {
    condition     = can(regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})/(\\d{1,2})$", var.cidrVpc))
    error_message = "Must be a valid IP CIDR range of the form x.x.x.x/x."
  }
}

variable "zoneId" {
  type        = string
  description = "The AZ for the VSwitch."
}

variable "cidrVSwitch" {
  type        = string
  default     = "10.0.0.0/16"
  description = "An available CIDR block for creating a new vswitch. The size of the vswitch should be big enough to hold instances of all your golden AMIs at a time"
  validation {
    condition     = can(regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})/(\\d{1,2})$", var.cidrVSwitch))
    error_message = "Must be a valid IP CIDR range of the form x.x.x.x/x."
  }
}

variable "instanceType" {
  type        = string
  default     = "ecs.c6.large"
  description = "Specify the the InstanceType compatible with all your golden image. This InstanceType will be used for launching building and vulnerability assessment of golden image."
}

variable "internetMaxBandwidthOut" {
  type        = number
  default     = 10
  description = "Unit: Mbit/s. Valid values: 0 to 100. No public ip if zero"
}

variable "approverRamUserName" {
  type        = string
  default     = ""
  description = "RAM user allowed for approval. This RAM user needs to have corresponding read and write permissions, and you can directly grant it AliyunOOSFullAccess permissions. This RAM user can approve/deny golden images."
}

variable "webHookUrl" {
  type        = string
  default     = ""
  description = "When image vulnerability assessment or manual approval is enabled, a notification will be sent through this WebHook."
}
