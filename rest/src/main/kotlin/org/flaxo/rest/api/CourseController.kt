package org.flaxo.rest.api

import arrow.core.Try
import arrow.core.getOrElse
import org.apache.logging.log4j.LogManager
import org.flaxo.core.stringStackTrace
import org.flaxo.model.CourseLifecycle
import org.flaxo.model.DataService
import org.flaxo.model.IntegratedService
import org.flaxo.model.data.Course
import org.flaxo.model.data.PlagiarismMatch
import org.flaxo.model.data.Task
import org.flaxo.model.data.views
import org.flaxo.moss.MossException
import org.flaxo.rest.service.CourseValidation
import org.flaxo.rest.service.codacy.CodacyService
import org.flaxo.rest.service.environment.RepositoryEnvironmentService
import org.flaxo.rest.service.git.GitService
import org.flaxo.rest.service.moss.MossService
import org.flaxo.rest.service.moss.MossTask
import org.flaxo.rest.service.response.ResponseService
import org.flaxo.rest.service.travis.TravisService
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.security.Principal
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Courses handling controller.
 */
@RestController
@RequestMapping("/rest/course")
class CourseController(private val dataService: DataService,
                       private val responseService: ResponseService,
                       private val environmentService: RepositoryEnvironmentService,
                       private val travisService: TravisService,
                       private val codacyService: CodacyService,
                       private val gitService: GitService,
                       private val mossService: MossService,
                       private val courseValidations: Map<IntegratedService, CourseValidation>
) {

    private val tasksPrefix = "task-"
    private val logger = LogManager.getLogger(CourseController::class.java)
    private val executor: Executor = Executors.newCachedThreadPool()

    /**
     * Imports a course from an existing git repository or the [principal] by its [repositoryName].
     */
    @PostMapping("/import")
    fun import(@RequestParam repositoryName: String,
               @RequestParam(required = false) description: String?,
               @RequestParam language: String,
               @RequestParam testingLanguage: String,
               @RequestParam testingFramework: String,
               principal: Principal
    ): ResponseEntity<Any> {
        logger.info("Trying to import course ${principal.name}/$repositoryName from an existing repository")

        val user = dataService.getUser(principal.name)
                ?: return responseService.userNotFound(principal.name)

        val githubId = user.githubId
                ?: return responseService.githubIdNotFound(user.nickname)

        val githubToken = user.credentials.githubToken
                ?: return responseService.githubTokenNotFound(user.nickname)

        gitService.with(githubToken)
                .getRepository(repositoryName)
                .takeIf { it.exists() }
                ?.also { repository ->
                    logger.info("Scanning for tasks of the importing course ${principal.name}/$repositoryName")
                    repository.branches()
                            .map { it.name }
                            .filter { it.startsWith(tasksPrefix) }
                            .also { tasksNames ->
                                dataService.createCourse(
                                        repositoryName,
                                        description,
                                        language,
                                        testingLanguage,
                                        testingFramework,
                                        tasksNames,
                                        user
                                )
                            }
                }
                ?: return responseService.bad("Github repository $githubId/$repositoryName")

        return responseService.ok("Course ${user.nickname}/$repositoryName was created")
    }

    /**
     * Creates a course and related git repository.
     *
     * @param courseName Name of the course and related git repository.
     * @param description Optional course description.
     * @param language Language of the course.
     * @param testLanguage Language used for tests.
     * @param testingFramework Testing framework which is used for testing.
     * @param numberOfTasks Number of tasks in the course and number of tasks branches
     * in the git repository.
     */
    @PostMapping("/create")
    @PreAuthorize("hasAuthority('USER')")
    @Transactional
    fun createCourse(@RequestParam courseName: String,
                     @RequestParam(required = false) description: String?,
                     @RequestParam language: String,
                     @RequestParam testingLanguage: String,
                     @RequestParam testingFramework: String,
                     @RequestParam numberOfTasks: Int,
                     principal: Principal
    ): ResponseEntity<Any> {
        logger.info("Trying to create course ${principal.name}/$courseName")

        val user = dataService.getUser(principal.name)
                ?: return responseService.userNotFound(principal.name)

        val githubToken = user.credentials.githubToken
                ?: return responseService.githubTokenNotFound(principal.name)

        logger.info("Producing course ${principal.name}/$courseName " +
                "environment: $language, $testingLanguage, $testingFramework")

        val environment = environmentService.produceEnvironment(
                language,
                testingLanguage,
                testingFramework
        )

        logger.info("Creating git repository for course ${principal.name}/$courseName")

        gitService.with(githubToken)
                .also {
                    it.createRepository(courseName).also {
                        it.createBranch("prerequisites")
                                .also { branch ->
                                    environment.getFiles().forEach { branch.commit(it) }
                                }
                                .createSubBranches(numberOfTasks, tasksPrefix)
                        it.addWebHook()
                    }
                }

        logger.info("Creating course ${principal.name}/$courseName in database")

        val course = dataService.createCourse(
                courseName = courseName,
                description = description,
                language = language,
                testingLanguage = testingLanguage,
                testingFramework = testingFramework,
                tasksPrefix = tasksPrefix,
                numberOfTasks = numberOfTasks,
                owner = user
        )

        logger.info("Course ${principal.name}/${course.name} has been successfully created")

        return responseService.ok(course.view())
    }

    /**
     * Returns full information about the current user's course with the given [courseName].
     *
     * @param courseName Name of the course and related git repository.
     */
    @GetMapping
    @PreAuthorize("hasAuthority('USER')")
    @Transactional(readOnly = true)
    fun course(@RequestParam courseName: String,
               principal: Principal
    ): ResponseEntity<Any> {
        val user = dataService.getUser(principal.name)
                ?: return responseService.userNotFound(principal.name)

        val course = dataService.getCourse(courseName, user)
                ?: return responseService.courseNotFound(principal.name, courseName)

        return responseService.ok(course.view())
    }

    /**
     * Returns full information about all courses of the given user.
     *
     * @param nickname User nickname to retrieve courses from.
     */
    @GetMapping("/all")
    @PreAuthorize("hasAuthority('USER')")
    @Transactional(readOnly = true)
    fun allCourses(@RequestParam nickname: String,
                   principal: Principal
    ): ResponseEntity<Any> =
            if (principal.name == nickname) {
                responseService.ok(dataService.getCourses(nickname).views())
            } else {
                responseService.forbidden()
            }

    /**
     * Deletes a current user course from the flaxo system and delete git repository as well.
     *
     * @param courseName Name of the course and related git repository.
     */
    @DeleteMapping("/delete")
    @PreAuthorize("hasAuthority('USER')")
    @Transactional
    fun deleteCourse(@RequestParam courseName: String,
                     principal: Principal
    ): ResponseEntity<Any> {
        logger.info("Trying to delete course ${principal.name}/$courseName")

        val user = dataService.getUser(principal.name)
                ?: return responseService.userNotFound(principal.name)

        val course = dataService.getCourse(courseName, user)
                ?: return responseService.courseNotFound(principal.name, courseName)

        val githubToken = user.credentials.githubToken
                ?: return responseService.githubTokenNotFound(principal.name)

        logger.info("Deactivating validations for ${principal.name}/$courseName course")

        course.state
                .activatedServices
                .mapNotNull { courseValidations[it] }
                .forEach { it.deactivate(course) }

        logger.info("Deleting course ${principal.name}/$courseName from the database")

        dataService.deleteCourse(courseName, user)

        logger.info("Deleting course ${principal.name}/$courseName git repository")

        gitService.with(githubToken)
                .getRepository(courseName)
                .delete()

        logger.info("Course ${principal.name}/$courseName has been successfully deleted")

        return responseService.ok()
    }

    /**
     * Composes course and enable travis builds and codacy analysis on
     * the git repository pull requests.
     *
     * Travis and codacy analysis is only activated if user has authorized
     * with related services in flaxo.
     *
     * It is supposed to be called after a user has finished filling tasks.
     *
     * @param courseName Name of the course and related git repository.
     */
    @PostMapping("/activate")
    @PreAuthorize("hasAuthority('USER')")
    @Transactional
    fun activate(@RequestParam courseName: String,
                 principal: Principal
    ): ResponseEntity<Any> {
        logger.info("Trying to compose course ${principal.name}/$courseName")

        val user = dataService.getUser(principal.name)
                ?.also {
                    it.credentials.githubToken ?: return responseService.githubTokenNotFound(it.nickname)
                    it.githubId ?: return responseService.githubIdNotFound(it.nickname)
                }
                ?: return responseService.userNotFound(principal.name)

        val course = dataService.getCourse(courseName, user)
                ?: return responseService.courseNotFound(principal.name, courseName)

        val activatedServices = courseValidations
                .mapNotNull { (serviceType, service) ->
                    Try { service.activate(course) }
                            .map { serviceType }
                            .getOrElse {
                                logger.info("$serviceType activation went bad for ${user.nickname}/$courseName " +
                                        "course due to: ${it.stringStackTrace()}")
                                null
                            }
                }
                .toSet()

        logger.info("Changing course ${user.nickname}/$courseName status to running " +
                "with activated services: $activatedServices")

        val activatedCourse = dataService.updateCourse(course.copy(
                state = course.state.copy(
                        lifecycle = CourseLifecycle.RUNNING,
                        activatedServices = activatedServices
                )
        ))

        logger.info("Course ${user.nickname}/$courseName has been successfully composed")

        return responseService.ok(activatedCourse.view())
    }

    /**
     * Activates codacy validations for specified [courseName].
     *
     * Course should be started.
     *
     * @param courseName Course name to activate codacy for.
     */
    @PostMapping("/activate/codacy")
    @PreAuthorize("hasAuthority('USER')")
    @Transactional
    fun activateCodacy(@RequestParam courseName: String,
                       principal: Principal
    ): ResponseEntity<Any> {
        logger.info("Trying to activate codacy for course ${principal.name}/$courseName")

        val user = dataService.getUser(principal.name)
                ?: return responseService.userNotFound(principal.name)

        val course = dataService.getCourse(courseName, user)
                ?: return responseService.courseNotFound(principal.name, courseName)

        if (course.state.lifecycle != CourseLifecycle.RUNNING)
            return responseService.bad("Course ${principal.name}/$courseName is not started yet")

        course.state
                .activatedServices
                .takeUnless { it.contains(IntegratedService.CODACY) }
                ?.let {
                    try {
                        codacyService.activate(course)

                        return dataService
                                .updateCourse(course.copy(
                                        state =
                                        course.state.copy(
                                                activatedServices =
                                                course.state.activatedServices + IntegratedService.CODACY
                                        )
                                ))
                                .let { responseService.ok(it.view()) }
                    } catch (e: Exception) {
                        logger.info("Codacy activation failed due to: $e")
                        return responseService.bad(e.toString())
                    }
                }
                ?: return responseService.bad("Codacy is already integrated with " +
                        "${principal.name}/$courseName course")
    }

    /**
     * Activates travis validations for specified [courseName].
     *
     * Course should be started.
     *
     * @param courseName Course name to activate travis for.
     */
    @PostMapping("/activate/travis")
    @PreAuthorize("hasAuthority('USER')")
    @Transactional
    fun activateTravis(@RequestParam courseName: String,
                       principal: Principal
    ): ResponseEntity<Any> {
        logger.info("Trying to activate travis for course ${principal.name}/$courseName")

        val user = dataService.getUser(principal.name)
                ?: return responseService.userNotFound(principal.name)

        val course = dataService.getCourse(courseName, user)
                ?: return responseService.courseNotFound(principal.name, courseName)

        if (course.state.lifecycle != CourseLifecycle.RUNNING)
            return responseService.bad("Course ${principal.name}/$courseName is not started yet")

        course.state
                .activatedServices
                .takeUnless { it.contains(IntegratedService.TRAVIS) }
                ?.let {
                    try {
                        travisService.activate(course)

                        return dataService
                                .updateCourse(course.copy(
                                        state =
                                        course.state.copy(
                                                activatedServices =
                                                course.state.activatedServices + IntegratedService.TRAVIS
                                        )
                                ))
                                .let { responseService.ok(it.view()) }
                    } catch (e: Exception) {
                        logger.info("Travis activation failed due to: $e")
                        return responseService.bad(e.toString())
                    }
                }
                ?: return responseService.bad("Travis is already integrated with " +
                        "${principal.name}/$courseName course")
    }

    /**
     * Starts current user's [courseName] plagiarism analysis.
     *
     * @param courseName Name of the course and related git repository.
     */
    @PostMapping("/analyse/plagiarism")
    @PreAuthorize("hasAuthority('USER')")
    @Transactional
    fun analysePlagiarism(@RequestParam courseName: String,
                          principal: Principal
    ): ResponseEntity<Any> {
        logger.info("Trying to start plagiarism analysis for ${principal.name}/$courseName")

        val user = dataService.getUser(principal.name)
                ?: return responseService.userNotFound(principal.name)

        val course = dataService.getCourse(courseName, user)
                ?: return responseService.courseNotFound(principal.name, courseName)

        logger.info("Extracting moss tasks for ${user.nickname}/$courseName")

        val mossTasks: List<MossTask> = mossService.extractMossTasks(course)

        logger.info("${mossTasks.size} moss tasks were extracted for ${user.nickname}/$courseName")

        logger.info("Scheduling moss tasks to execute for ${user.nickname}/$courseName")

        val submittedTasks = submitMossTasksExecution(mossTasks, course)

        logger.info("Moss plagiarism analysis has been started for ${principal.name}/$courseName")

        Try {
            CompletableFuture.allOf(*submittedTasks).get()
        }.getOrElse { e ->
            logger.error("Moss plagiarism analysis went bad for some of the tasks: ${e.stringStackTrace()}")
        }

        val scheduledTasksNames: List<String> =
                mossTasks.map { it.taskName }
                        .map { it.split("/").last() }

        return responseService.ok("Plagiarism analysis has finished for tasks: $scheduledTasksNames")
    }

    private fun submitMossTasksExecution(mossTasks: List<MossTask>,
                                         course: Course
    ): Array<CompletableFuture<Void>> = synchronized(executor) {
        mossTasks.map { mossTask ->
            CompletableFuture.runAsync(Runnable { analyseMossTask(mossTask, course, course.tasks) }, executor)
        }
    }.toTypedArray()

    private fun analyseMossTask(mossTask: MossTask, course: Course, courseTasks: Set<Task>) {
        val branch = mossTask.taskName.split("/").last()

        val task = courseTasks.find { it.branch == branch }
                ?: throw MossException("Moss task ${mossTask.taskName} aim course task $branch " +
                        "wasn't found for course ${course.name}")

        logger.info("Analysing ${mossTask.taskName} moss task for ${mossTask.base.size} bases files " +
                "and ${mossTask.solutions.size} solutions files")

        val mossResult =
                mossService.client(course.language)
                        .base(mossTask.base)
                        .solutions(mossTask.solutions)
                        .analyse()

        logger.info("Moss task analysis ${mossTask.taskName} has finished successfully and " +
                "is available by ${mossResult.url}")

        val plagiarismReport = dataService.addPlagiarismReport(
                task = task,
                url = mossResult.url.toString(),
                matches = mossResult.matches().map {
                    PlagiarismMatch(
                            student1 = it.students.first,
                            student2 = it.students.second,
                            lines = it.lines,
                            url = it.link,
                            percentage = it.percentage
                    )
                }
        )

        dataService.updateTask(task.copy(
                plagiarismReports = task.plagiarismReports.plus(plagiarismReport)
        ))
    }

    /**
     * Synchronize all validations results.
     */
    @PostMapping("/sync")
    @Transactional
    fun synchronize(@RequestParam courseName: String,
                    principal: Principal
    ): ResponseEntity<Any> {
        logger.info("Syncing ${principal.name} $courseName course validations")

        val user = dataService.getUser(principal.name)
                ?: return responseService.userNotFound(principal.name)

        val course = dataService.getCourse(courseName, user)
                ?: return responseService.courseNotFound(principal.name, courseName)

        course.takeIf { it.state.lifecycle == CourseLifecycle.RUNNING }
                ?.state
                ?.activatedServices
                ?.mapNotNull { courseValidations[it] }
                ?.forEach { it.refresh(course) }
                ?: return responseService.bad("Course ${user.nickname}/${course.name} " +
                        "is not running to be synchronized")

        return responseService.ok("Course validations are synchronized")
    }

}