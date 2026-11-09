-- 고객용 RAG DB(customer-assistant-service, ADR-0051). 새 볼륨에서만 실행된다 — 기존 볼륨이면 한 번 직접:
--   docker compose -f deploy/local/ai-assistant/docker-compose.yml exec ai-assistant-postgres psql -U pmart -c "CREATE DATABASE customer_assistant_service"
CREATE DATABASE customer_assistant_service;
