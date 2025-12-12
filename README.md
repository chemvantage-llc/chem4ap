# Chem4AP

A full-stack chemistry learning platform with a React-based front-end exercises app and a Java/Maven back-end.

## Prerequisites

- Node.js 16+ and npm
- Java 21
- Maven 3.6+
- Google Cloud SDK (for App Engine deployment)

## Setup Instructions

### 1. Clone the Repository

```bash
git clone https://github.com/chemvantage-llc/chem4ap.git
cd chem4ap
```

### 2. Install Node.js and npm

If not already installed:

**macOS (using Homebrew):**
```bash
brew install node
```

**Linux (Ubuntu/Debian):**
```bash
sudo apt-get update
sudo apt-get install nodejs npm
```

**Windows:**
Download from [nodejs.org](https://nodejs.org/)

Verify installation:
```bash
node --version
npm --version
```

### 3. Build the Front-End Exercises

Install dependencies and build the React app:

```bash
cd front-end/exercises
npm install
npm run build
```

The build output will be automatically deployed to `back-end/src/main/webapp/exercises/`.

### 4. Back-End Configuration

Before building the back-end, configure Google Cloud credentials and project settings:

**Configure Google Cloud Project:**
```bash
gcloud config set project YOUR_PROJECT_ID
gcloud auth application-default login
```

**Update pom.xml (if needed):**
Edit `back-end/pom.xml` and replace `GCLOUD_CONFIG` with your actual project ID:
```xml
<googleCloudProjectId>your-gcloud-project-id</googleCloudProjectId>
```

**Back-End Dependencies:**
The back-end is a Spring Boot 3.1 application (Java 17) with:
- Spring Boot Web (embedded Tomcat server)
- Google Cloud Datastore/Firestore integration
- LTI 1.3 protocol support for education platforms
- RESTful API endpoints for the React front-end

Key Java components in `src/main/java/org/chemvantage/chem4ap/`:
- `SpringbootMain.java` - Application entry point
- `Exercises.java` - Exercise content and scoring
- `User.java`, `Score.java` - User management and performance tracking
- `LTIMessage.java`, `LTIRequest.java` - LTI protocol handlers
- Math parser utilities for chemistry equation validation

### 5. Build and Deploy the Back-End

Navigate to the back-end directory and build:

```bash
cd back-end
mvn clean package
```

This will:
- Compile Java code
- Include the React front-end build from `src/main/webapp/exercises/`
- Package everything into a Spring Boot WAR file
- Run all tests

To deploy to Google App Engine:
```bash
mvn appengine:deploy
```

**Local Testing:**
```bash
mvn exec:java
```
Starts the back-end at `http://localhost:8080`


## Development Workflow

**Full Build & Deploy Pipeline:**

1. **Develop front-end (React):**
   ```bash
   cd front-end/exercises
   npm start
   ```
   Runs dev server at `http://localhost:3000/exercises`

2. **Build front-end for production:**
   ```bash
   npm run build
   ```
   Outputs to `back-end/src/main/webapp/exercises/`

3. **Build and run back-end locally:**
   ```bash
   cd ../../back-end
   mvn exec:java
   ```
   Runs at `http://localhost:8080`
   - Serves React front-end from `/exercises`
   - Provides API endpoints for exercise data, scoring, and user management

4. **Test full integration:**
   - Navigate to `http://localhost:8080/exercises` in your browser
   - Verify React app loads and communicates with back-end APIs

5. **Production deployment:**
   ```bash
   mvn clean package appengine:deploy
   ```
   Deploys both front-end and back-end to Google App Engine

## Back-End Architecture

**Spring Boot 3.1 Application** (Java 17)

- **Web Framework:** Spring Boot Starter Web (Tomcat)
- **Storage:** Google Cloud Datastore/Firestore for persistence
- **API:** RESTful endpoints consumed by React front-end
- **LTI Integration:** Full LTI 1.3 support for integrating with learning management systems (Canvas, Blackboard, etc.)
- **Authentication:** LTI-based or custom token validation

**Key Endpoints:**
- `POST /api/exercises` - Submit exercise responses
- `GET /api/exercises/{id}` - Get exercise content
- `POST /api/scores` - Record student scores
- `GET /api/user/report` - Retrieve user performance data
- `/exercises/*` - Serves the React SPA

The front-end communicates with these REST endpoints to load exercises, submit answers, and display feedback and scoring.

## Project Structure

```
chem4ap/
├── front-end/
│   └── exercises/        # React SPA for exercises
│       ├── src/
│       ├── public/
│       └── package.json
├── back-end/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/     # Java backend code
│   │   │   ├── webapp/   # Static assets (includes React build)
│   │   │   └── appengine/
│   │   └── test/
│   └── pom.xml
└── README.md
```

## Dependencies & Security

The project uses npm workspaces to consolidate dependencies and `package.json` overrides to manage transitive vulnerability fixes. 

View security audit:
```bash
npm audit
```

## Support

For issues or questions, please open a GitHub issue or contact the maintainers.
