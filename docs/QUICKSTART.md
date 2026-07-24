# Quick Start

Get CodeAtlas running in 5 minutes.

## Prerequisites

- **JDK 17+**
- **Maven 3.8+**
- **Node.js 20+**
- **MySQL 5.7+** (running on localhost:3306)
- **Neo4j 5.x** (running on localhost:7687)
- **Redis 7.x** (running on localhost:6379)

## 1. Clone & Configure

```bash
git clone https://github.com/zferrys/codeatlas.git
cd codeatlas
```

Set up environment variables:

```bash
export MYSQL_PASSWORD=your_mysql_password
export NEO4J_PASSWORD=your_neo4j_password
export CODEATLAS_JWT_SECRET=$(openssl rand -base64 64)
```

Optional — AI analysis (requires API key):

```bash
export ANTHROPIC_API_KEY=sk-ant-xxx    # Claude (primary)
export DEEPSEEK_API_KEY=sk-xxx         # DeepSeek (fallback)
```

## 2. Initialize Database

```bash
# Create the database
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS codeatlas DEFAULT CHARACTER SET utf8mb4;"

# Flyway runs automatically on first boot — no manual migration needed
```

## 3. Build & Run Backend

```bash
mvn clean package -DskipTests -pl codeatlas-server -am
java -jar codeatlas-server/target/*.jar
```

The API starts at `http://localhost:8080`. Swagger UI is at `http://localhost:8080/doc.html`.

## 4. Build & Run Frontend

```bash
cd codeatlas-web
npm install
npm run dev
```

Open `http://localhost:5173` in your browser.

## 5. First Scan

1. Register an account
2. Create a project — paste a GitHub URL or upload a ZIP
3. Click "Scan" — the pipeline runs: clone → parse → analyze → graph
4. Open the **Code Map** to explore your 3D architecture

## Troubleshooting

| Symptom | Check |
|---------|-------|
| Can't connect to MySQL | Verify MySQL is running: `mysqladmin ping` |
| Neo4j errors | Verify Neo4j is running and credentials match |
| AI analysis skipped | Set `ANTHROPIC_API_KEY` or `DEEPSEEK_API_KEY` |
| Frontend can't reach API | Check Vite proxy config in `vite.config.js` |
