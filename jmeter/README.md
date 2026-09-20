# JMeter 压测

当前仓库也提供了更轻量的 Python 压测脚本：`scripts/load_test.py`。如果只需要快速验证并发能力，优先使用 Python 脚本即可。

```powershell
python .\scripts\load_test.py --users 50 --actions 200 --seed-links 100 --ramp-up 60
```

脚本先创建短链池，再进入稳态流量阶段。默认稳态比例为：95% 重定向、4% 详情查询、1% 创建短链；可通过 `--redirect-ratio`、`--info-ratio`、`--create-ratio` 调整。`--think-time-ms` 可增加用户请求间隔，脚本只使用 Python 标准库，无需额外安装依赖；它会输出每个接口的请求数、错误率、平均响应时间、P95 和最大响应时间。

压测计划 shortlink-load-test.jmx 覆盖完整用户链路：

1. POST /shorten：每次迭代生成唯一 URL，并在 JSR223 Groovy 预处理器中计算 PoW nonce。
2. GET /{id}：关闭自动跟随重定向，验证重定向接口本身的吞吐和响应时间。
3. GET /getInfo/{id}：查询刚创建的短链详情。

## 命令行运行

建议使用非 GUI 模式运行，并将结果写入 JTL：

    jmeter -n -t .\jmeter\shortlink-load-test.jmx -Jusers=50 -Jramp_up=60 -Jloops=20 -Jpow_difficulty=2 -Jhost=127.0.0.1 -Jport=8080 -l .\jmeter\results.jtl -e -o .\jmeter\report

参数：users 并发线程数（默认 10）；ramp_up 启动时间秒数（默认 30）；loops 每用户完整链路次数（默认 10）；pow_difficulty 必须与服务端 app.pow-difficulty 一致（默认 2）；host 和 port 指定服务地址。

先启动 Redis 和 Spring Boot 服务，再执行压测。默认配置下服务使用 302 重定向，因此计划关闭 JMeter 自动跟随重定向；如果使用 dev profile，重定向状态码是 200，但其余链路仍可执行。

结果查看：打开生成的 jmeter/report/index.html，重点关注 90/95/99 分位响应时间、错误率、吞吐量，以及服务端 CPU、堆内存、Hikari 连接池、Redis 和 HSQLDB 资源使用情况。
