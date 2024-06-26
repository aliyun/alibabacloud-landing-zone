provider "alicloud" {
  region = var.target_region_id
}

provider "alicloud" {
  region = var.source_region_id
  alias  = "source_image"
}

# describe source image
data "alicloud_images" "source_image" {
  provider = alicloud.source_image
  owners   = "self"
  image_id = var.source_image_id
}

# copy from source
# set name and tags as same as source image
resource "alicloud_image_copy" "image" {
  source_image_id  = var.source_image_id
  source_region_id = var.source_region_id
  image_name       = var.image_name == "" ? data.alicloud_images.source_image.images[0].name : var.image_name
  description      = var.description == "" ? data.alicloud_images.source_image.images[0].description : var.description
  tags             = data.alicloud_images.source_image.images[0].tags
}
