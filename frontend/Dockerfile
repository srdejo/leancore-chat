# syntax=docker/dockerfile:1
# One image per app of the workspace: docker build --build-arg APP=user-app|admin-app .
FROM node:24-alpine AS build
ARG APP
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npx ng build ${APP} --configuration production

FROM nginx:1.29-alpine
ARG APP
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist/${APP}/browser /usr/share/nginx/html
EXPOSE 80
