# EllanTimedOrders

艾尔岚跨服限时随机订单系统。插件通过 Redis 同步订单状态和全服公告，支持 CraftEngine 物品与 TheBrewingProject 配方订单。

## 功能

- 自动或管理员手动发布限时订单
- 玩家点击公告接单，NPC 提交
- 跨服同步订单、接单状态和截止时间
- BossBar 显示自己接取的订单倒计时
- 玩家可自行开启或关闭全服订单详细推送
- 推送偏好按玩家 UUID 保存在 Redis，切换服务器后仍然有效

## 玩家命令

```text
/ellanorder
/ellanorder notify
/ellanorder notify on
/ellanorder notify off
/ellanorder notify status
```

说明：

- 不写参数时，`/ellanorder notify` 会在开启和关闭之间切换。
- 默认开启全服订单公告。
- 关闭后不再接收发布、接取、完成、超时和撤回等全服广播。
- 关闭只影响公屏广播，不影响自己接单、BossBar、订单提交和个人交付反馈。
- 控制台始终保留全部订单事件日志。

别名：

```text
/限时订单
/timedorder
```

## 管理员命令

```text
/ellanorder admin
/ellanorder cancel [订单编号]
/ellanorder reload
```

管理命令需要 `ellanorders.admin` 权限。

## 环境要求

- Paper 26.3 (build target: `26.3.build.157-beta`)
- Java 25
- Redis
- CraftEngine
- ExcellentEconomy
- TheBrewingProject
- FancyNpcs

## 构建

第三方插件没有公开 Maven 仓库，需要先将以下 JAR 放入 `libs/`：

- CraftEngine
- ExcellentEconomy
- Nightcore
- TheBrewingProject
- FancyNpcs
- BungeeCord Chat
- Adventure API

然后执行：

```bash
./gradlew build
```

构建产物位于 `build/libs/EllanTimedOrders-0.4.0.jar`。

## Redis 键

| 键 | 用途 |
| --- | --- |
| `ellan:timedorders:active[:slot]` | 当前订单 |
| `ellan:timedorders:claim[:slot]` | 接单状态 |
| `ellan:timedorders:notify:<uuid>` | 玩家的订单公屏推送偏好 |
| `ellan:timedorders:events` | 跨服订单事件频道 |

## 从 0.3.0 升级

1. 备份旧插件 JAR 和配置。
2. 替换为 `EllanTimedOrders-0.4.0.jar`。
3. 重启服务器。
4. 玩家可使用 `/ellanorder notify status` 查看当前设置。

## 开源许可

MIT
