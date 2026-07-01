package com.example.finaluri

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

enum class TaskPriority { LOW, MEDIUM, HIGH }
enum class TaskStatus { TODO, IN_PROGRESS, DONE }

// შეცვალეთ ეს ნაწილი
data class TaskEntity(
    val title: String,
    val description: String,
    val category: String,
    val priority: Int,
    val status: Boolean,
    val dueDate: String,
    val id: String? = null
)

sealed interface TaskUiState {
    object Loading : TaskUiState
    data class Success(val tasks: List<TaskEntity>) : TaskUiState
    data class Error(val message: String) : TaskUiState
}

interface TaskApiService {
    @GET("syllabus")
    suspend fun getMockTasks(): List<TaskEntity>
    @POST("syllabus")
    suspend fun addTask(@Body task: TaskEntity): TaskEntity

}

object RetrofitClient {
    private const val BASE_URL = "https://6a4504c9aab3faec3f693e82.mockapi.io/todo/api/v1/"
    private val okHttpClient = OkHttpClient.Builder().build()

    val api: TaskApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TaskApiService::class.java)
    }
}

class TaskRepository(private val api: TaskApiService) {
    private val _tasks = MutableStateFlow<List<TaskEntity>>(emptyList())
    val allTasks = _tasks.asStateFlow()

    suspend fun fetchTasksFromApi(): List<TaskEntity> {
        val remoteTasks = api.getMockTasks()
        _tasks.value = remoteTasks
        return remoteTasks
    }

    suspend fun addTask(task: TaskEntity) {
        val response = api.addTask(task) //
        _tasks.value = _tasks.value + response //
    }
}



class TaskViewModel(private val repository: TaskRepository) : ViewModel() {
    private val _uiState = MutableStateFlow<TaskUiState>(TaskUiState.Loading)
    val uiState = _uiState.asStateFlow()

    private val _eventFlow = MutableSharedFlow<String>()
    val eventFlow = _eventFlow.asSharedFlow()

    init {
        fetchData()
    }

    private fun fetchData() {
        viewModelScope.launch {
            try {
                repository.fetchTasksFromApi()
                repository.allTasks.collect { tasks ->
                    _uiState.value = TaskUiState.Success(tasks)
                }
            } catch (e: Exception) {
                _uiState.value = TaskUiState.Error("შეცდომა: ${e.message}")
            }
        }
    }

    fun addTask(title: String, desc: String, cat: String) {
        viewModelScope.launch {
            if (title.isBlank()) {
                _eventFlow.emit("შეავსეთ სათაური!")
                return@launch
            }
            try {
                val newTask = TaskEntity(
                    title = title,
                    description = desc,
                    category = cat,
                    priority = (1..5).random(),
                    status = false,
                    dueDate = "2024-12-31"
                )
                repository.addTask(newTask)
                _eventFlow.emit("წარმატებით დაემატა!")
            } catch (e: Exception) {
                _eventFlow.emit("დამატება ვერ მოხერხდა: ${e.message}")
            }
        }
    }
}


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = TaskRepository(RetrofitClient.api)
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T = TaskViewModel(repo) as T
        }

        setContent {
            val viewModel: TaskViewModel = viewModel(factory = factory)
            val navController = rememberNavController()
            val uiState by viewModel.uiState.collectAsState()
            val context = LocalContext.current

            // Event observer
            LaunchedEffect(Unit) {
                viewModel.eventFlow.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
            }

            NavHost(navController, startDestination = "dashboard") {
                composable("dashboard") {
                    DashboardScreen(uiState,
                        onCategoryClick = { navController.navigate("list/$it") },
                        onAddTaskClick = { navController.navigate("add_task") })
                }
                composable("list/{cat}") { backStack ->
                    val cat = backStack.arguments?.getString("cat")
                    TaskListScreen(uiState, cat,
                        onBack = { navController.popBackStack() },
                        onTaskClick = { id -> navController.navigate("detail/$id") })
                }
                composable("detail/{id}") { backStack ->
                    val id = backStack.arguments?.getString("id")
                    TaskDetailScreen(uiState, id, onBack = { navController.popBackStack() })
                }
                // Botsheet
                dialog("add_task") {
                    AddTaskBottomSheet(
                        onDismiss = { navController.popBackStack() },
                        onSave = { t, d, c -> viewModel.addTask(t, d, c); navController.popBackStack() }
                    )
                }
            }
        }
    }
}


@Composable
fun DashboardScreen(uiState: TaskUiState, onCategoryClick: (String) -> Unit, onAddTaskClick: () -> Unit) {
    val categories = listOf("სამსახური", "პირადი", "სწავლა", "ჯანმრთელობა")
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("მთავარი", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(20.dp))

        when (uiState) {
            is TaskUiState.Loading -> {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator() // პროგრესის ინდიკატორი
                }
            }
            is TaskUiState.Error -> {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(uiState.message, color = Color.Red)
                }
            }
            is TaskUiState.Success -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(categories) { cat ->
                        Card(modifier = Modifier.height(100.dp).clickable { onCategoryClick(cat) }) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Text(cat, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        Button(onClick = onAddTaskClick, modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
            Icon(Icons.Default.Add, null)
            Text("დავალების დამატება")
        }
    }
}

@Composable
fun TaskListScreen(uiState: TaskUiState, category: String?, onBack: () -> Unit, onTaskClick: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
            Text(category ?: "დავალებები", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }

        when (uiState) {
            is TaskUiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is TaskUiState.Error -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("შეცდომა: ${uiState.message}", color = Color.Red)
                }
            }
            is TaskUiState.Success -> {
                val allTasks = uiState.tasks
                // ვცდილობთ ფილტრაციას, თუ არაფერია - ვაჩვენებთ ყველას
                val filteredTasks = allTasks.filter { it.category.contains(category ?: "", ignoreCase = true) }
                val tasksToShow = if (filteredTasks.isEmpty()) allTasks else filteredTasks

                if (tasksToShow.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("API ცარიელია (მონაცემები არ არის)")
                    }
                } else {
                    LazyColumn {
                        items(tasksToShow) { task ->
                            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onTaskClick(task.title) }) {
                                ListItem(
                                    headlineContent = { Text(task.category) }, // აჩვენებს ქვეყანას (Georgia და ა.შ.)
                                    supportingContent = { Text("Status: ${if(task.status) "Done" else "Active"} | Prio: ${task.priority}") },
                                    overlineContent = { Text(task.title) } // აჩვენებს ID-ს
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}@Composable
fun TaskDetailScreen(uiState: TaskUiState, taskId: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val task = (uiState as? TaskUiState.Success)?.tasks?.find { (it.id ?: it.title) == taskId }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
        task?.let {
            Text(it.title, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(it.category, color = Color.Gray)
            Spacer(modifier = Modifier.height(16.dp))
            Text(it.description, fontSize = 18.sp)

            Spacer(modifier = Modifier.weight(1f))

            // intent-ის გამოყენება
            Button(onClick = {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "ჩემი დავალება: ${it.title}")
                }
                context.startActivity(Intent.createChooser(intent, "გაზიარება"))
            }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Share, null)
                Text("გაზიარება")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTaskBottomSheet(onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(20.dp).fillMaxWidth()) {
            Text("ახალი დავალება", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("სათაური") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("აღწერა") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { onSave(title, desc, "პირადი") }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Text("შენახვა")
            }
            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}