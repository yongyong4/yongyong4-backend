# Yongyong Backend

용인 대중교통 이동 전략 추천 서비스의 Spring Boot 백엔드 초기 프로젝트입니다.

## 개발 환경

- Java 21
- Spring Boot 4.1.2
- Gradle 8.14.3 (Groovy DSL)
- Spring Web MVC, Spring Boot Actuator
- Database / JPA / Security: **미설정** (ERD, 인증 정책 확정 후 추가)

## 초기 실행

1. JDK 21이 설치돼 있는지 확인합니다: `java -version`
2. Gradle 8.14.3을 설치하거나 IntelliJ의 Gradle 설정에서 사용합니다.
3. 프로젝트 루트에서 실행합니다.

**Windows PowerShell**

```powershell
gradle bootRun
```

**macOS / Linux**

```bash
gradle bootRun
```

브라우저에서 확인:

- `http://localhost:8080/api/v1/health` -> `status: UP`
- `http://localhost:8080/actuator/health` -> `status: UP`

포트 변경 (PowerShell 예시):

```powershell
$env:SERVER_PORT = '8081'
gradle bootRun
```

## Gradle Wrapper를 생성한 뒤 GitHub에 함께 올리기

이 초기 배포본에는 **공식 Gradle Wrapper JAR와 실행 스크립트가 포함되어 있지 않습니다.** 생성할 때 인터넷이 연결된 PC에서 다음을 프로젝트 루트에 실행하세요.

```powershell
gradle wrapper --gradle-version 8.14.3 --distribution-type bin
```

그러면 다음 파일이 생성됩니다.

- `gradlew`
- `gradlew.bat`
- `gradle/wrapper/gradle-wrapper.jar`
- `gradle/wrapper/gradle-wrapper.properties`

**이 네 파일을 모두 Git에 포함해야** 팀원들이 Gradle 별도 설치 없이 Windows에서 `./gradlew.bat bootRun`으로 실행할 수 있습니다.

Wrapper 생성이 불가능하면 IntelliJ의 Spring Initializr에서 동일 버전으로 생성한 프로젝트의 wrapper 파일을 복사하는 것도 가능합니다. 공식 Gradle Wrapper 파일만 사용하세요.

## 폴더 구조

```text
src/main/java/com/yongyong/transit/
  YongyongApplication.java        # 시작 클래스
  health/HealthController.java    # 기본 동작 확인
src/main/resources/application.yml
src/test/java/com/yongyong/transit/
  YongyongApplicationTests.java   # Spring Context 로딩 확인
```

## 이후 작업

1. ODsay / TMAP 경로 탐색 API 테스트 및 GBIS ID 매칭 확인
2. 수집 중인 버스 데이터 DB 스키마 공유받기
3. ERD 확정 후 Spring Data JPA, DB 드라이버, 데이터소스 설정 추가
4. 요구사항정의서 v0.6에 맞게 route, trip, recommendation 모듈 구성
5. API 명세서 확정 후 DTO / controller / service 구현

API 키는 GitHub에 올리지 마세요. 환경변수 또는 gitignore 처리한 로컬 설정 파일을 사용하세요.

## 초기 GitHub 업로드

GitHub에서 빈 저장소를 만든 뒤:

```powershell
git init
git add .
git commit -m "chore: initialize Spring Boot backend"
git branch -M main
git remote add origin https://github.com/OWNER/REPOSITORY.git
git push -u origin main
```

`OWNER/REPOSITORY`는 실제 저장소 주소로 바꾸세요.

## 참고

GitHub Actions에서는 Gradle 8.14.3을 별도로 설치해 테스트하므로 Wrapper 생성 전에도 CI 구성을 사용할 수 있습니다.

이 프로젝트의 기본 파일은 작성됐지만, 배포 환경에서 의존성을 다운로드할 수 없어 **실제 Gradle 빌드 성공 여부는 검증하지 못했습니다.**
