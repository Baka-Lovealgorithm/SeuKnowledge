# syntax=docker/dockerfile:1

FROM node:20-alpine AS build
WORKDIR /web

COPY web/package.json web/package-lock.json ./
RUN npm ci

COPY web/ ./
ARG VITE_ENABLE_AI_EXTRACTION=false
ENV VITE_ENABLE_AI_EXTRACTION=${VITE_ENABLE_AI_EXTRACTION}
RUN npm run build

FROM nginx:1.27-alpine
COPY deploy/nginx/default.conf /etc/nginx/conf.d/default.conf
COPY --from=build /web/dist/ /usr/share/nginx/html/

EXPOSE 80
