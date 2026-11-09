# syntax=docker/dockerfile:1
#
# Shidur for the computer: the built web app and the server it needs (web/README.md).
# Build from the repository root, which holds version.properties:
#   docker build -f web/Dockerfile -t shidur-web .

FROM node:22-alpine AS build
WORKDIR /src/web
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY web/ ./
COPY version.properties /src/version.properties
ARG SHIDUR_SHA=docker
ENV SHIDUR_SHA=${SHIDUR_SHA}
RUN npm run build

FROM node:22-alpine
# ffmpeg re-encodes AC-3 audio for browsers that cannot play it (/api/remux). The server runs
# without it and says so; --build-arg WITH_FFMPEG=0 leaves it out for a smaller image.
ARG WITH_FFMPEG=1
RUN if [ "${WITH_FFMPEG}" = "1" ]; then apk add --no-cache ffmpeg; fi
WORKDIR /app/web
ENV NODE_ENV=production HOST=0.0.0.0 PORT=8787
COPY --from=build /src/version.properties /app/version.properties
# The server's own dependencies only (`dependencies`: firebase-admin, for the Google sign-in mode);
# the browser's libraries are already in dist/.
COPY web/package.json web/package-lock.json ./
RUN npm ci --omit=dev --no-audit --no-fund && npm cache clean --force
COPY --from=build /src/web/dist ./dist
COPY web/server ./server
USER node
EXPOSE 8787
HEALTHCHECK --interval=60s --timeout=5s CMD wget -qO- "http://127.0.0.1:${PORT}/api/health" > /dev/null || exit 1
CMD ["node", "server/index.mjs"]
