# Production Helm: not implemented in the starter

生产 Chart 需要定义：各能力的依赖与可选组件、独立 ServiceAccount、资源请求/限制、PDB、探针、HPA、NetworkPolicy、TLS、OIDC、Secret 引用、备份与恢复、镜像 digest、持久卷和租户存储路由。

此目录只占位，故意不提供看似可用但没有完成安全/HA配置的生产 chart。不要把开发 Compose 直接转换成生产模板并宣称可用。
