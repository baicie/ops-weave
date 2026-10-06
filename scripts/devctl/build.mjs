import { mkdirSync, readFileSync, writeFileSync, existsSync } from 'node:fs'
import { spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { dirname, relative } from 'node:path'
import { architectures, artifact, hostTarget, output, platforms, root } from './artifacts.mjs'

try {
  const args = process.argv.slice(2)
  if (args.length > 1 || args.length === 1 && args[0] !== '--all') throw new Error('用法：pnpm dev:build，或 pnpm dev:package')
  const targets = args[0] === '--all'
    ? platforms.flatMap(platform => architectures.map(arch => ({ platform, arch })))
    : [hostTarget()]
  for (const target of targets) {
    const binary = artifact(target)
    mkdirSync(dirname(binary), { recursive: true })
    console.log(`构建 opsweave-dev ${target.platform}/${target.arch}`)
    const result = spawnSync('go', ['-C', 'scripts/devctl', 'build', '-trimpath', '-ldflags=-s -w', '-o', binary, '.'], {
      cwd: root,
      env: { ...process.env, CGO_ENABLED: '0', GOOS: target.platform, GOARCH: target.arch },
      stdio: 'inherit',
      shell: false,
    })
    if (result.error) throw new Error(`无法执行 Go（${result.error.code ?? 'UNKNOWN'}），构建需要 Go 1.23+`)
    if (result.status !== 0) throw new Error(`构建 ${target.platform}/${target.arch} 失败（退出码 ${result.status ?? 'signal'}）`)
  }
  const checksums = platforms.flatMap(platform => architectures.map(arch => artifact({ platform, arch })))
    .filter(existsSync)
    .map(binary => `${createHash('sha256').update(readFileSync(binary)).digest('hex')}  ${relative(output, binary).replaceAll('\\', '/')}`)
  writeFileSync(`${output}/SHA256SUMS`, `${checksums.join('\n')}\n`)
  writeFileSync(`${output}/README.txt`, `OpsWeave 开发服务管理器\n\n选择操作系统和架构对应的 opsweave-dev 可执行文件，放在 OpsWeave 仓库内运行。\nWindows 使用 .exe；Linux/macOS 使用无扩展名的文件，必要时 chmod +x。\n\nopsweave-dev                    交互菜单\nopsweave-dev start all          启动全部\nopsweave-dev restart web        重启前端\nopsweave-dev status             查看状态\nopsweave-dev logs platform      查看后端日志\nopsweave-dev stop all           停止全部\n\n仓库内也可使用 pnpm ops restart web，自动选择 dist/devctl 下的当前平台产物。\n管理器运行无需 Go；应用仍需要仓库、Java 21、Rust、Node 和已安装的 Web 依赖。\n源码保留在 scripts/devctl，构建见 docs/runbooks/dev-manager.md。\nSHA256SUMS 提供二进制校验值。包中不包含环境文件或凭据。\n`)
  console.log(`产物已生成：${output}`)
} catch (error) {
  console.error(error.message)
  process.exitCode = 1
}
