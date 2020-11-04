variable "vpc_id"{
    default="vpc-bp1xxxxxxx"
}

variable "nat_name"{
    default="nat_test_name"
}


variable "eip_bandwidth"{
    default="10"
}

variable "eip_internet_charge_type"{
    default="PayByBandwidth"
}

variable "common_bandwidth_package_enabled" {
    default=false
}

variable "common_bandwidth_package_name"{
    default="tf_cbp"
}

variable "common_bandwidth_package_bandwidth"{
    default="1000"
}

variable "common_bandwidth_package_internet_charge_type"{
    default="PayByBandwidth"
}

