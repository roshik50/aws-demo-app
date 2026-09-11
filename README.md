# AWS Assignment L1 — Employee Management API

A Spring Boot 3 REST API backed by MySQL, built to satisfy the "Develop and Deploy a Scalable
Web Application with Monitoring and Automation" assignment: RDS in a private subnet, ECS Fargate
behind an ALB, CloudFormation IaC, and CloudWatch monitoring/logging.

## Project Structure
```
aws-demo-app/
├── pom.xml
├── Dockerfile
├── cloudformation/
│   └── template.yaml
└── src/main/java/com/nagarro/awsdemo/
    ├── AwsDemoApplication.java
    ├── model/Employee.java
    ├── repository/EmployeeRepository.java
    ├── controller/EmployeeController.java
    └── config/GlobalExceptionHandler.java
```

## Tech Stack
- Java 17, Spring Boot 3.3 (Web, Data JPA, Validation, Actuator)
- MySQL (RDS) in production, H2 in-memory for local dev
- Docker (multi-stage build) → Amazon ECR → ECS Fargate
- Application Load Balancer, CloudWatch, SNS, CloudFormation

---

## API Reference

Base path: `/api/employees`

| Method | Endpoint                | Description                     | Body |
|--------|--------------------------|----------------------------------|------|
| GET    | `/api/employees`         | List all employees               | — |
| GET    | `/api/employees/{id}`    | Get one employee by ID           | — |
| POST   | `/api/employees`         | Create a new employee            | JSON (see below) |
| PUT    | `/api/employees/{id}`    | Update an existing employee      | JSON (see below) |
| DELETE | `/api/employees/{id}`    | Delete an employee               | — |
| GET    | `/api/employees/ping`    | Lightweight liveness/demo check  | — |
| GET    | `/actuator/health`       | Health check (used by ALB + ECS) | — |

**Request/response body (POST / PUT):**
```json
{
  "name": "Jane Doe",
  "email": "jane.doe@example.com",
  "department": "Engineering"
}
```

**Example responses:**
- `POST /api/employees` → `201 Created` with the saved employee (includes generated `id`)
- `GET /api/employees/999` (not found) → `404 Not Found`
- Validation failure → `400 Bad Request` with a field → message map, e.g.
  `{"email": "Email must be valid"}`
- `GET /api/employees/ping` →
  ```json
  {"status": "ok", "timestamp": "2026-09-10T10:00:00Z", "servedBy": "ip-10-0-1-23"}
  ```

**cURL examples:**
```bash
curl -X POST http://<ALB-DNS>/api/employees \
  -H "Content-Type: application/json" \
  -d '{"name":"Jane Doe","email":"jane@example.com","department":"Engineering"}'

curl http://<ALB-DNS>/api/employees
curl http://<ALB-DNS>/actuator/health
```

---

## Running Locally
```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
# App runs on http://localhost:8080, using an in-memory H2 DB
```

## Building the Docker Image
```bash
docker build -t aws-demo-app .
docker run -p 8080:8080 --env-file .env aws-demo-app
```

---

## AWS Deployment Guide (maps to the assignment's four requirement sections)

### 1. Database — RDS in a private subnet
1. In the VPC console, confirm/create a VPC with **2 public subnets** (for the ALB) and
   **2 private subnets** (for RDS + ECS tasks) across two AZs.
2. RDS console → **Create database** → Standard create → Engine: MySQL (or PostgreSQL).
3. Templates: **Dev/Test** (or Production if you want Multi-AZ).
4. Set master username/password. Instance class: `db.t3.micro`.
5. Under Connectivity: select your VPC, choose the **DB subnet group** made of the private
   subnets, and set **Public access = No**.
6. Create a dedicated **RDS security group** that allows inbound port `3306` only from the
   ECS service's security group (not `0.0.0.0/0`).
7. Under Additional configuration, enable **Automated backups** and set a **retention period**
   (e.g., 1 days) — note this value for your screenshots/deliverable.
8. Create the database and note the endpoint once available.

### 2. Container Deployment — ECS Fargate behind an ALB
1. **Push the image to ECR:**
   ```bash
   aws ecr create-repository --repository-name aws-demo-app --region <region>
   aws ecr get-login-password --region <region> | docker login --username AWS \
     --password-stdin <account-id>.dkr.ecr.<region>.amazonaws.com

   docker build -t aws-demo-app .
   docker tag aws-demo-app:latest <account-id>.dkr.ecr.<region>.amazonaws.com/aws-demo-app:latest
   docker push <account-id>.dkr.ecr.<region>.amazonaws.com/aws-demo-app:latest
   ```
2. **Create an ECS cluster** (Fargate launch type) in the ECS console.
3. **Create a Task Definition**: Fargate, 0.25 vCPU / 0.5 GB, container port `8080`, image URI
   from ECR, environment variables `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`
   (or better, reference `DB_PASSWORD` from Secrets Manager), and log configuration →
   `awslogs` driver pointing at a CloudWatch Logs group (e.g. `/ecs/aws-demo-app`).
4. **Create an Application Load Balancer** (internet-facing, public subnets) with a target
   group of type **IP**, health check path `/actuator/health`.
5. **Create the ECS Service**: launch type Fargate, private subnets, the ECS security group
   (allowing inbound 8080 from the ALB SG only), attach it to the ALB target group, desired
   count 2.
6. Open the **ALB's DNS name** in a browser (e.g. `http://<alb-dns>/api/employees/ping`) to
   confirm the app is reachable.
7. **Scale the service**: edit the service, change **Desired tasks** (e.g. 2 → 4), save, and
   watch new tasks start in the ECS console's "Tasks" tab.
8. **Clean up** when done: delete the service, then the cluster, to avoid ongoing charges.

### 3. Infrastructure as Code — CloudFormation
The `cloudformation/template.yaml` file recreates the RDS + ECS + ALB + ECR setup above as code.

1. Validate: `aws cloudformation validate-template --template-body file://cloudformation/template.yaml`
2. Create a change set (review before deploying):
   ```bash
   aws cloudformation create-change-set \
     --stack-name aws-demo-stack \
     --template-body file://cloudformation/template.yaml \
     --capabilities CAPABILITY_IAM \
     --change-set-name initial-deploy \
     --parameters ParameterKey=VpcId,ParameterValue=<vpc-id> \
                  ParameterKey=PublicSubnetIds,ParameterValue=<subnet-1>\\,<subnet-2> \
                  ParameterKey=PrivateSubnetIds,ParameterValue=<subnet-3>\\,<subnet-4> \
                  ParameterKey=DBUsername,ParameterValue=admin \
                  ParameterKey=DBPassword,ParameterValue=<password> \
                  ParameterKey=AlertEmail,ParameterValue=<your-email>
   ```
3. Review the change set in the console (or `describe-change-set`), then execute it:
   ```bash
   aws cloudformation execute-change-set --stack-name aws-demo-stack --change-set-name initial-deploy
   ```
4. The template is parameterized for **DB instance class**, **task CPU/memory**, and uses
   `AWS::Region`/`AWS::AccountId` pseudo-parameters, so it is portable across regions.
5. The `ECRRepository` resource is managed in the same template, per the requirement.

### 4. Monitoring & Observability
Already wired into the template (`MonitoringDashboard`, `HighCpuAlarm`, `AlarmTopic`,
`Container Insights` cluster setting, and the `awslogs` log driver in the Task Definition).
If doing this manually instead:
1. CloudWatch → **Dashboards** → create one with widgets for `AWS/ECS` (CPU/Memory,
   dimensions: ClusterName + ServiceName) and `AWS/RDS` (CPU, DatabaseConnections).
2. CloudWatch → **Alarms** → create an alarm on ECS `CPUUtilization`, action: notify an
   **SNS topic** that has your email subscribed (confirm the subscription email).
3. ECS cluster settings → enable **Container Insights** to get per-task/container metrics.
4. Confirm ECS task logs are flowing into the CloudWatch Logs group referenced in the Task
   Definition's log configuration.

### Deliverables checklist
- [ ] Screenshots: RDS config (private subnet, backups), ECR push, ECS cluster/service running,
      ALB URL loading the app, scaling event, CloudFormation change set + stack, CloudWatch
      dashboard, alarm + SNS email, Container Insights, CloudWatch Logs.
- [ ] This repository (code) + working ALB URL while the stack is up.
