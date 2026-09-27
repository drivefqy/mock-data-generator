# Mock Data Generator — DataGrip 插件

在 DataGrip 里为数据库表批量生成模拟数据 —— 不需要写脚本,也不依赖外部工具。
选择数据源、选择表、按列调整生成规则,一键插入。

---

## 功能

- **44 种内置生成器** —— 中英文姓名、手机号、邮箱、身份证号、IP、MAC、URL、银行卡、车牌、
  中文地址、公司名称、品牌、产品、UUID、自增、序列号、整数 / 小数区间、枚举、正则模板、日期时间。
- **按列智能推荐** —— 每列都会根据列名与 JDBC 类型自动推断一条推荐规则(以圆点标记),
  通常只需要调整少数据列。
- **默认安全** —— 自增列与唯一列会被显式处理(跳过,或从当前 `MAX` 值续接);
  唯一约束冲突时自动做一次性修复,而不是让整批插入失败。
- **结构以真实数据库为准** —— 列清单、主键与唯一约束都从真实连接读取,
  内省缓存过期也不会静默漏掉 `NOT NULL` 列。
- **支持 1 到 1 亿行** —— JDBC 批量插入(每批 500 行,事务内执行,失败即回滚)。
  提交前可以先预览前 5 行的 SQL。
- **中英文界面** —— 对话框内可直接切换界面语言。

## 环境要求

- DataGrip **2026.2**(build 262)或更高版本 低版本未测试
- 已配置好的数据源。任何能通过 JDBC 访问的数据库都可以;
  PostgreSQL、MySQL / MariaDB、SQL Server 与 H2 均已实测。

## 安装

**从插件市场安装** —— DataGrip → Settings → Plugins → Marketplace → 搜索 *Mock Data Generator*。

**从本地文件安装** —— Settings → Plugins → ⚙ → *Install Plugin from Disk…* →
选择 `mock-data-generator-<版本号>.zip` → 重启 IDE。
插件安装包

## 使用

1. **Tools → 生成 Mock 数据…**,或在数据库工具窗口中右击某张表直接打开(默认选中该表)。
2. 选择数据源与目标表(可在搜索框中输入以过滤)。
3. 逐列调整:选择生成规则,再点击「**参数**」单元格进行配置
   (枚举候选值、数值区间、时间范围、正则模板)。若希望部分行写入 SQL `NULL`,
   可设置该列的**空值比例**。
4. 输入生成条数,可以先点击「**预览 5 行**」,确认后执行插入。

自增列与标识列默认跳过 —— 不用管它们,交给数据库自行填充。

<img width="1733" height="776" alt="image" src="https://github.com/user-attachments/assets/a180f872-a352-4f43-b2c7-86d1d38522d2" />

## 隐私

插件完全在你的 IDE 内本地运行:

- 无网络连接、无遥测、无埋点、无崩溃上报。
- 只从你选择的数据源读取表结构(续接自增序列时,需要读取该列当前的最大值)。
- 只有在你点击插入按钮之后,才会写入你明确选择的表。
- 不读取也不存储凭据;日志中的 JDBC URL 已做脱敏处理。

## 从源码构建

需要 **JDK 21** 与 Gradle 8.x(已在 8.14.3 上验证)。

```bash
# 产出可分发安装的 zip -> build/distributions/mock-data-generator-<版本号>.zip
gradle buildPlugin

# 纯逻辑冒烟测试(无需 IDE),输出 smoke_result.txt
gradle smokeTest -x instrumentCode
```

若要针对本机的 IDE 安装进行构建,需要提供 `datagripHome`(你的 DataGrip 安装路径)——
可以写在 Gradle 用户目录的配置里(`<GRADLE_USER_HOME>/gradle.properties`),
也可以用命令行传入 `-PdatagripHome=...`。与本机相关的路径刻意不纳入本仓库。

## 安全

所有涉及外部数据的语句都使用**绑定参数**;标识符按驱动自身的引用规则加引号,
不做字符串拼接。插件只声明 `platform` 与 `database` 两个依赖,
不打开任何网络连接,也不执行外部命令。

## 许可证

[MIT](LICENSE) © 2026 Dérive
