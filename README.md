# jenkins-atom-ssh-exec

在另一台机器上跑一段 shell，并把输出带回来。

ssh-steps 用 Groovy DSL 包 JSch；能搬的部分是「把这些命令行在那边跑掉，告诉我结果」。命令**通过 stdin 交给 `bash -s`**，不经过引号拼装的参数——引号、换行、`$` 都不会被吃掉。

- 远端退出码不等于 `expectedExitCode` 时按 `failOnError` 决定是否失败
- 255 是 ssh 自己失败（密钥/主机/端口），归 `USER`；超时归 `ENVIRONMENT`
- stdout 与 stderr 分别进构建日志，出错时把输出尾部放进报错里
- 私钥可给路径或内容（内容写 0600 临时文件，用完删除）

```bash
mvn verify    # packages dist/ssh-exec/ and runs src/test/java/atom/SshExecIT.java against a throwaway sshd
```
