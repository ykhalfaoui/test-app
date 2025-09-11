# KYC Skeleton (Spring Boot 3 • Spring Modulith • H2)

## Run
```bash
mvn spring-boot:run
```
- H2 Console: http://localhost:8080/h2-console
  - JDBC URL: `jdbc:h2:mem:kyc;MODE=Oracle`
  - User: `sa`

## Try it
1) Create a party:
```bash
curl -s -X POST http://localhost:8080/api/parties -H "Content-Type: application/json" -d '{"type":"Customer","subType":"Retail","externalRef":"PIVOT-1"}' | jq
```

2) Trigger a qualified hit (replace `<partyId>` with the created ID):
```bash
curl -s -X POST http://localhost:8080/api/hits/qualified -H "Content-Type: application/json" -d '{"partyId":"<partyId>","hitType":"SANCTION"}'
```

This will publish an internal event and start a minimal review with one target (NAME_SCREENING) for the pivot.
Check application logs to see Modulith event flow and integration stub.
