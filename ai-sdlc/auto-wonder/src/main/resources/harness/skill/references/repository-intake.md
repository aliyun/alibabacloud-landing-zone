# 仓库信息收集

以下仅说明仓库字段及可编辑示例，可增删仓库、修改名称、URL、分支、描述和关系。初始化时将这些字段合入 var.md 的同一份项目反馈清单，不另发仓库收集消息；整份清单单独作为一条 Chief 消息发送供一键复制。进度提示放在前一条说明消息中。不能默认创建示例中的仓库。

```text
本项目中的仓库信息如下：
server：https://CUSTOMER_REPOSITORY_HOST/CUSTOMER_REPOSITORY_PATH/server.git，默认分支为CUSTOMER_DEFAULT_BRANCH
front：https://CUSTOMER_REPOSITORY_HOST/CUSTOMER_REPOSITORY_PATH/front.git，默认分支为CUSTOMER_DEFAULT_BRANCH
interface：https://CUSTOMER_REPOSITORY_HOST/CUSTOMER_REPOSITORY_PATH/interface.git，默认分支为CUSTOMER_DEFAULT_BRANCH
middleware：https://CUSTOMER_REPOSITORY_HOST/CUSTOMER_REPOSITORY_PATH/middleware.git，默认分支为CUSTOMER_DEFAULT_BRANCH

仓库的描述如下：
server仓库是本工程的服务端代码，技术栈是Java，所有的后端业务代码在这个仓库开发
front仓库是本工程的客户端代码，技术栈是Vue，所有的前端代码在这个仓库开发
interface仓库是本工程中所有的接口定义，技术栈是Java，所有的接口定义在这个仓库开发
middleware是本工程中所有的数据库、Redis操作仓库，技术栈是Java，所有的数据库和 Redis 代码在这个仓库开发

仓库的关系如下：
front仓库依赖server仓库
server仓库依赖interface仓库和middleware仓库
```

解析为仓库name/url/defaultBranch/description及依赖边，展示摘要确认。名称须满足平台单段路径规则；重复、未定义关系端点、示例URL、缺失分支先澄清。无需关系时由客户明确说明，不臆造依赖。

通过平台领域能力创建或复用当前空间仓库，不创建远程Git仓库。依赖方向是依赖方→被依赖方，relationType=DEPENDS_ON；示例为front→server、server→interface、server→middleware。相同关系不重复创建。

把客户确认的项目仓库绑定到模板小队所有数字人：AW_AUTO_CR、AW_AUTO_REQ_CLARIFIER、AW_AUTO_PM为READ；AW_AUTO_FS_DEV、AW_AUTO_QA为WRITE。未来角色根据职责确定：需要修改提交代码或合并为WRITE，分析/澄清/管理/审核为READ；不明确先询问，不默认授予WRITE，更不授予ADMIN。权限矩阵进入确认快照；不凭显示名分配，不删除其他仓库绑定。已有绑定权限不同须在差异中明确展示并经确认更新，不能靠重复绑定假定权限已改变。

仓库登记与平台绑定不等于远程Git凭据已就绪。引导客户在执行环境配置授权访问方式，秘密仅填受控引用；就绪检查需有实际只读访问证据，不为验证写权限创建无关提交。
