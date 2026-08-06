FROM node:24.13.0-alpine3.23 AS dependencies

WORKDIR /app

COPY package.json package-lock.json ./

RUN npm ci


FROM node:24.13.0-alpine3.23 AS builder

WORKDIR /app

ENV NEXT_TELEMETRY_DISABLED=1

COPY --from=dependencies /app/node_modules ./node_modules

COPY . .

RUN mkdir -p public \
    && npm run build


FROM node:24.13.0-alpine3.23 AS runtime

WORKDIR /app

ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
ENV HOSTNAME=0.0.0.0
ENV PORT=3000

RUN addgroup \
        --system \
        --gid 1001 \
        nodejs \
    && adduser \
        --system \
        --uid 1001 \
        --ingroup nodejs \
        nextjs

COPY --from=builder \
    --chown=nextjs:nodejs \
    /app/public \
    ./public

COPY --from=builder \
    --chown=nextjs:nodejs \
    /app/.next/standalone \
    ./

COPY --from=builder \
    --chown=nextjs:nodejs \
    /app/.next/static \
    ./.next/static

USER nextjs

EXPOSE 3000

CMD ["node", "server.js"]