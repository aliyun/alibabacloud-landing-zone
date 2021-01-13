output "vswitches_app" {
  value = {
    for o in keys(module.vpc_vswitch) : o => module.vpc_vswitch[o].vswitch_app
  }
}