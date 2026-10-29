FROM node:24-alpine AS build
WORKDIR /app
RUN corepack enable
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY . .
RUN pnpm build

# The server has no dependencies: Node runs its TypeScript as is.
FROM node:24-alpine
WORKDIR /app
ARG BUILD=dev
ENV NODE_ENV=production BUILD=$BUILD
COPY package.json ./
COPY shared shared
COPY server server
COPY --from=build /app/dist dist
USER node
EXPOSE 3150
HEALTHCHECK --interval=30s --timeout=3s CMD wget -qO /dev/null http://127.0.0.1:3150/api/health || exit 1
CMD ["node", "server/main.ts"]
