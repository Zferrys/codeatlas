# Extension Guide

How to extend CodeAtlas with new languages, rules, and AI models.

## Adding a New Language Parser

CodeAtlas currently supports Java via `JavaParserService`. To add a new language:

1. Create a parser class implementing the analysis contract:

```java
// In codeatlas-engine/src/main/java/com/codeatlas/engine/parser/
public class PythonParserService {
    public List<ClassSummaryResult> analyzeDirectory(Path directory) {
        // 1. Walk directory tree for .py files
        // 2. Parse each file (AST or regex)
        // 3. Build ClassSummaryResult list
        //    - fqn: module.path.ClassName
        //    - layer: CONTROLLER / SERVICE / REPOSITORY / DOMAIN / UTILITY
        //    - dependencies: list of imported FQNs
        //    - methods, line count, annotations
        return results;
    }
}
```

2. Register as a Spring Bean in `EngineBeanConfig`:

```java
@Bean
public PythonParserService pythonParserService() {
    return new PythonParserService();
}
```

3. Update `ScanServiceImpl.executeScan()` to select the parser based on `project.getLanguage()`.

## Adding a New Architecture Rule

Rules are defined in the `constitution_rule` table and evaluated by `RuleEngine`.

```sql
INSERT INTO constitution_rule (name, description, severity, rule_definition, is_enabled, project_id)
VALUES (
  'No Util Depend On Service',
  'Utility classes must not depend on Service-layer classes',
  'WARNING',
  '{"type": "dependency", "from_layer": "UTILITY", "to_layer": "SERVICE", "operator": "NONE"}',
  1,
  NULL  -- NULL = global default rule
);
```

Rule definition JSON schema:

```json
{
  "type": "dependency | naming | size | annotation",
  "from_layer": "CONTROLLER | SERVICE | REPOSITORY | DOMAIN | UTILITY",
  "to_layer": "...",
  "operator": "AT_MOST | NONE | ALL",
  "threshold": 0
}
```

## Adding a New AI Model

1. Implement `AiClient` interface:

```java
public class OpenAiClient implements AiClient {
    @Override
    public AiResponse chat(AiRequest request) { ... }

    @Override
    public void chatStream(AiRequest request, StreamCallback callback) { ... }

    @Override
    public String getModelName() { return "gpt-4o"; }

    @Override
    public boolean healthCheck() { ... }
}
```

2. Register in `EngineBeanConfig.aiClient()` — add to the fallback chain:

```java
String openaiKey = System.getenv("OPENAI_API_KEY");
if (openaiKey != null && !openaiKey.isEmpty()) {
    clients.add(new OpenAiClient(openaiKey));
}
```

The `AiClientFallbackChain` automatically tries clients in order (first registered = highest priority).

## Adding a New Visualization Mode

The map page in `ProjectMap.vue` supports switching between renderers:

1. Create a new component in `src/components/map/` (e.g., `CodeMapHeatmap.vue`)
2. Accept the same props as `CodeMap.vue`:
   ```javascript
   const props = defineProps({
     projectId: { type: [Number, String], required: true }
   })
   ```
3. Register in `ProjectMap.vue`:
   ```html
   <CodeMapHeatmap v-else-if="viewMode === 'heatmap'" :project-id="projectId" />
   ```

## Database Migrations

All schema changes go through Flyway:

```
codeatlas-server/src/main/resources/db/migration/
├── V1__init_schema.sql
├── V2__add_rbac.sql
├── V3__add_neo4j_fields.sql
└── V4__add_ai_audit_log.sql    ← Add new migrations here with V5, V6, ...
```

Run `mvn flyway:migrate` to apply manually, or they run automatically on boot.
