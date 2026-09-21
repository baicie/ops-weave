FROM node:22-slim AS build
WORKDIR /workspace
RUN corepack enable
# Generate and commit the root pnpm-lock.yaml before building.
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml .npmrc ./
COPY apps/web-console/package.json apps/web-console/package.json
RUN corepack prepare --activate && pnpm install --frozen-lockfile
COPY apps/web-console apps/web-console
RUN pnpm build:web

FROM nginx:1.28-alpine
COPY --from=build /workspace/apps/web-console/dist /usr/share/nginx/html
