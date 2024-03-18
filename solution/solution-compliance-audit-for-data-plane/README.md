# 通过配置审计和函数计算实现云主机风险巡检

通过配置审计和函数计算，实现企业客户云上主机自动化风险巡检的方案，满足多账号体系下，主机风险的持续巡检和统一监控。

## 目录结构

```
─ source
  ├── function
  │   ├── ecs-patch-baseline
  │   │   ├── index.js
  │   │   └── package.json
  │   └── ecs-timezone
  │       ├── index.js
  │       └── package.json
  └── ros
      ├── create-cross-account-command-inspection-role.yaml
      └── create-cross-account-patch-inspection-role.yaml
```

- source/function/ecs-patch-baseline：操作系统补丁基线巡检的函数计算示例代码
- source/function/ecs-timezone：对 ECS 主机示例进行时区巡检的函数计算示例代码
- source/ros/create-cross-account-command-inspection-role.yaml：ROS 资源栈组的模版代码。该模版用于跨账号创建巡检角色，函数计算通过扮演该巡检角色，跨账号调用云助手，在目标 ECS 主机实例中运行自定义命令脚本，以此实现对系统、软件配置，比如：主机内核参数、主机时区、软件版本等进行检测。
- source/ros/create-cross-account-patch-inspection-role.yaml：ROS 资源栈组的模版代码。该模版用于跨账号创建巡检角色，函数计算通过扮演该巡检角色，跨账号使用 OOS 的补丁管理，根据补丁基线，对 ECS 主机实例的操作系统进行补丁扫描，及时发现操作系统缺失的补丁。