# CSFreeze 传送冻结插件

Minecraft **1.20.1** Bukkit/Spigot/Paper 插件：把玩家随机传送到预设坐标点并冻结，用于 CS 小游戏/躲猫猫等玩法，支持主持人白名单。

- 版本：1.1.0
- 兼容：Bukkit / Spigot / Paper 1.20.1（需 Java 17）

---

## 功能

- `/cs point` 站在方块上快速设置坐标点（1~100 个，重复设置同编号自动覆盖旧坐标）
- `/cs start` 将所有在线玩家随机分配到坐标点并冻结（**1 人 1 点，不会重复**）
- `/cs jiechu` 一键解除所有玩家的冻结
- **主持人白名单**：白名单玩家不参与传送、不会被冻结
- 坐标点与白名单均持久化保存到 `config.yml`，可直接编辑文件修改

---

## 安装

1. 将 `CSFreeze-1.1.0.jar` 放入服务器 `plugins` 文件夹
2. 重启服务器（或 `/reload`）
3. 使用 OP 账号在游戏内配置坐标点即可

---

## 命令

| 命令 | 说明 | 权限 |
|---|---|---|
| `/cs point <1-100>` | 站在方块上设置坐标点；重复输入同编号会覆盖旧坐标 | cs.admin |
| `/cs start` | 所有玩家随机传送到坐标点并冻结（1 人 1 点），白名单玩家跳过 | cs.admin |
| `/cs jiechu` | 解除所有玩家冻结，恢复正常行动 | cs.admin |
| `/cs whitelist add <名字>` | 将玩家加入白名单（主持人），不区分大小写 | cs.admin |
| `/cs whitelist remove <名字>` | 将玩家移出白名单 | cs.admin |
| `/cs whitelist list` | 查看当前白名单 | cs.admin |

> 控制台（Console）也可以执行 `start` / `jiechu` / `whitelist` 命令。

### 权限

| 权限节点 | 默认 | 说明 |
|---|---|---|
| `cs.admin` | op | 使用全部 `/cs` 命令 |

---

## 配置文件 `plugins/CSFreeze/config.yml`

坐标点和白名单都保存在这里，服务器运行时可随时编辑（改完需重启或 `/reload` 生效）。

```yaml
# 坐标点：编号 1-100
points:
  1:
    world: world
    x: 100.5
    y: 64.0
    z: 100.5
    yaw: 0.0
    pitch: 0.0

# 主持人白名单（小写保存，匹配时不区分大小写）
whitelist:
  - steve
  - alex
```

> 注意：在游戏内使用 `/cs point` 或 `/cs whitelist` 修改后，配置文件中的注释会被覆盖删除，属正常现象。

---

## 行为细节

- **冻结**：玩家无法改变所在方块（可以转头），行走/飞行速度归零；传送、击杀或挤压造成的位移同样被拦截
- **坐标点不足**：`/cs start` 时只传送与坐标点数量相等的玩家，并在控制台提示剩余玩家
- **白名单生效时机**：`/cs start` 时跳过；若玩家被冻结期间加入白名单，会立即解除冻结
- **玩家退出/服务器关闭**：自动解除对应冻结，不会出现"卡死"残留
- **世界缺失**：坐标点所在世界未加载时，该点会被跳过并提示

---

## 从源码构建

环境要求：JDK 17+

```
# 1. 编译（lib 下需有 paper-api 1.20.1 及依赖 jar）
javac --release 17 -encoding UTF-8 -cp "lib/*" -d out src/com/csplugin/freeze/CSPlugin.java

# 2. 复制资源文件
copy resources\plugin.yml out\
copy resources\config.yml out\

# 3. 打包
jar cf CSFreeze-1.1.0.jar -C out .
```

项目结构：

```
CSFreeze/
├── src/com/csplugin/freeze/CSPlugin.java   # 插件主类（全部逻辑）
├── resources/
│   ├── plugin.yml                          # 插件声明（命令/权限）
│   └── config.yml                          # 默认配置文件
├── lib/                                    # 编译依赖（paper-api 等）
├── out/                                    # 编译输出
└── servertest/                             # 本地 Paper 测试服（可删除）
```

---

## 更新日志

- **1.1.0**：新增主持人白名单 `/cs whitelist add/remove/list`，白名单玩家免传送免冻结
- **1.0.0**：基础功能 `/cs point` `/cs start` `/cs jiechu`，坐标持久化

## 测试

已在 Paper 1.20.1（build 196）实测：插件加载、全部命令、配置持久化均正常。站在方块上设置坐标与游戏内传送冻结链路建议在真实服务器上用玩家账号确认。
