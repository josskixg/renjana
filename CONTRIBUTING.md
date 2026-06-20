# Contributing to Renjana

Thank you for your interest in contributing to Renjana! This document provides guidelines and information for contributors.

## 📋 Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Getting Started](#getting-started)
- [Development Setup](#development-setup)
- [Making Changes](#making-changes)
- [Submitting Changes](#submitting-changes)
- [Code Style](#code-style)
- [Reporting Issues](#reporting-issues)

## 🤝 Code of Conduct

This project adheres to a simple code of conduct:
- Be respectful and constructive
- Focus on the problem, not the person
- Welcome newcomers and help them learn
- Keep discussions on-topic

## 🚀 Getting Started

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or later
- JDK 17
- Android SDK (API 34)
- Git

### Fork and Clone

1. Fork the repository on GitHub
2. Clone your fork locally:
   ```bash
   git clone https://github.com/YOUR_USERNAME/Renjana.git
   cd Renjana
   ```

3. Add upstream remote:
   ```bash
   git remote add upstream https://github.com/original/Renjana.git
   ```

## 💻 Development Setup

### Import Project

1. Open Android Studio
2. Select "Open an existing project"
3. Navigate to the cloned Renjana directory
4. Wait for Gradle sync to complete

### Build Configuration

The project uses:
- **Gradle:** 8.11
- **Kotlin:** 1.9.20
- **AGP:** 8.5.0
- **Compose:** BOM 2023.10.01

### Running the App

1. Connect an Android device or start an emulator (API 29+)
2. Select the `app` module
3. Click Run or press Shift+F10

## 🔧 Making Changes

### Branch Naming

Use descriptive branch names:
```bash
git checkout -b feature/add-plugin-system
git checkout -b fix/crash-on-instance-launch
git checkout -b docs/improve-readme
```

### Commit Messages

Follow conventional commits:
```
<type>(<scope>): <description>

[optional body]

[optional footer]
```

Types:
- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation only
- `style`: Code style changes
- `refactor`: Code refactoring
- `test`: Adding tests
- `chore`: Maintenance tasks

Example:
```
feat(hooks): add Play Integrity API bypass

Implement bypass for Play Integrity API to allow virtualized apps
to pass integrity checks.

Closes #123
```

### Testing Your Changes

Before submitting:

1. **Build successfully:**
   ```bash
   ./gradlew clean assembleDebug
   ```

2. **Run tests:**
   ```bash
   ./gradlew test
   ```

3. **Test on device:**
   - Install and run the app
   - Test the specific feature you modified
   - Check for crashes or issues

## 📤 Submitting Changes

### Pull Request Process

1. **Update your fork:**
   ```bash
   git fetch upstream
   git checkout main
   git merge upstream/main
   ```

2. **Rebase your branch:**
   ```bash
   git checkout your-branch
   git rebase main
   ```

3. **Push to your fork:**
   ```bash
   git push origin your-branch
   ```

4. **Create Pull Request:**
   - Go to your fork on GitHub
   - Click "New Pull Request"
   - Select your branch and base branch (main)
   - Fill out the PR template

### PR Requirements

- ✅ Clear description of changes
- ✅ Related issue number (if applicable)
- ✅ Tests for new features
- ✅ Updated documentation (if applicable)
- ✅ Passing CI checks

### Review Process

1. Maintainers will review your PR
2. Address any feedback or requested changes
3. Once approved, maintainers will merge

## 📝 Code Style

### Kotlin Style Guide

Follow the [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html):

- Use 4 spaces for indentation
- Maximum line length: 120 characters
- Use meaningful variable names
- Add KDoc for public APIs

### Example

```kotlin
/**
 * Manages virtual app instances.
 *
 * Provides methods to create, launch, and manage isolated
 * app instances within the container.
 */
class InstanceManager(
    private val context: Context,
    private val instanceDao: InstanceDao
) {
    /**
     * Creates a new instance of the specified app.
     *
     * @param packageName Package name of the app
     * @param apkPath Path to the APK file
     * @return Result containing the created instance or error
     */
    suspend fun createInstance(
        packageName: String,
        apkPath: String
    ): Result<Instance> {
        // Implementation
    }
}
```

### Compose Style

```kotlin
@Composable
fun InstanceCard(
    instance: Instance,
    onLaunch: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        // Content
    }
}
```

## 🐛 Reporting Issues

### Bug Reports

Include:
- **Description:** Clear description of the issue
- **Steps to Reproduce:** Detailed steps
- **Expected Behavior:** What should happen
- **Actual Behavior:** What actually happens
- **Environment:** Android version, device model
- **Logs:** Relevant logcat output

### Feature Requests

Include:
- **Description:** Clear description of the feature
- **Use Case:** Why this feature is needed
- **Proposed Solution:** How it could be implemented
- **Alternatives:** Other solutions considered

## 📚 Documentation

When adding features:
- Update README.md if it's a user-facing feature
- Add KDoc comments to public APIs
- Update relevant wiki pages
- Add code examples where helpful

## 🧪 Testing

### Writing Tests

- Unit tests for business logic
- UI tests for Compose screens
- Integration tests for complex workflows

### Test Structure

```kotlin
class InstanceManagerTest {
    
    @Test
    fun `createInstance returns success for valid APK`() {
        // Arrange
        val manager = InstanceManager(context, dao)
        
        // Act
        val result = runBlocking {
            manager.createInstance("com.test.app", "/path/to/app.apk")
        }
        
        // Assert
        assertTrue(result.isSuccess)
    }
}
```

## 📞 Questions?

- Open a [Discussion](../../discussions) for questions
- Check existing [Issues](../../issues) for similar problems
- Review the [Wiki](../../wiki) for documentation

---

Thank you for contributing to Renjana! 🎉
