# Flowstead engine image. SPDX-License-Identifier: GPL-3.0-or-later
# Build:  docker build -t flowstead-engine engine/
# Run:    see deploy/docker-compose.yml (Caddy terminates HTTPS in front of this container)
# Keys:   docker exec <container> dotnet /app/Flowstead.Engine.Api.dll keys add <device>

FROM mcr.microsoft.com/dotnet/sdk:8.0 AS build
RUN apt-get update && apt-get install -y --no-install-recommends binutils zstd curl ca-certificates gcc libc6-dev \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY scripts/ scripts/
COPY native/ native/
# Reuse a pre-downloaded package if the build context has one (CI cache), else download.
COPY .dl/ .dl/
RUN scripts/fetch-dwsim.sh /opt/dwsim
COPY Directory.Build.props ./
COPY src/ src/
RUN dotnet publish src/Flowstead.Engine.Worker -c Release -o /app/worker -p:DwsimBin=/opt/dwsim \
 && dotnet publish src/Flowstead.Engine.Api -c Release -o /app \
 && dotnet publish src/Flowstead.Engine.Spike -c Release -o /app/spike -p:DwsimBin=/opt/dwsim

FROM mcr.microsoft.com/dotnet/aspnet:8.0
RUN apt-get update && apt-get install -y --no-install-recommends libfontconfig1 libgomp1 \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --home /var/lib/flowstead flowstead \
    && mkdir -p /var/lib/flowstead && chown flowstead /var/lib/flowstead
COPY --from=build /opt/dwsim /opt/dwsim
COPY --from=build /app /app
ENV DWSIM_HOME=/opt/dwsim \
    ASPNETCORE_HTTP_PORTS=8080 \
    Engine__WorkerPath=/app/worker/Flowstead.Engine.Worker.dll \
    Engine__DatabasePath=/var/lib/flowstead/flowstead.db
VOLUME /var/lib/flowstead
USER flowstead
WORKDIR /app
EXPOSE 8080
ENTRYPOINT ["dotnet", "/app/Flowstead.Engine.Api.dll"]
