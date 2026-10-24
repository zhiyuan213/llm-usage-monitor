FROM node:24-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci --no-audit
COPY . .
RUN npm run build
FROM nginx:stable-alpine
COPY --chmod=644 nginx-main.conf /etc/nginx/nginx.conf
COPY --chmod=644 nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
# Git checkout and static-file copying may preserve an operator's umask 077.
RUN find /usr/share/nginx/html -type d -exec chmod 755 {} + \
 && find /usr/share/nginx/html -type f -exec chmod 644 {} +
USER nginx
EXPOSE 8080
