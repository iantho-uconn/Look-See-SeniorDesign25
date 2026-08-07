package looksee.angelll.com.uifiles

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.Calendar

@Composable
fun PayInfo() {
    // Card Information
    var cardProvider by remember { mutableStateOf("Visa") }
    var cardNum by remember { mutableStateOf("") }

    // Note: Java Calendar months are 0-indexed, so we add 1
    val currentMonth = Calendar.getInstance().get(Calendar.MONTH) + 1
    var expireMonth by remember { mutableIntStateOf(currentMonth) }

    val currentYear = Calendar.getInstance().get(Calendar.YEAR)
    var expireYear by remember { mutableIntStateOf(currentYear) }

    var cvv by remember { mutableStateOf("") }
    val cardProviders = listOf("Visa", "Mastercard")

    // Billing Information
    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    var state by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var postCode by remember { mutableStateOf("") }
    var address1 by remember { mutableStateOf("") }
    var address2 by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }

    val stateArray = listOf(
        "Alabama", "Alaska", "Arizona", "Arkansas", "California", "Colorado", "Connecticut",
        "Delaware", "Florida", "Georgia", "Hawaii", "Idaho", "Illinois", "Indiana", "Iowa",
        "Kansas", "Kentucky", "Louisiana", "Maine", "Maryland", "Massachusetts", "Michigan",
        "Minnesota", "Mississippi", "Missouri", "Montana", "Nebraska", "Nevada", "New Hampshire",
        "New Jersey", "New Mexico", "New York", "North Carolina", "North Dakota", "Ohio",
        "Oklahoma", "Oregon", "Pennsylvania", "Rhode Island", "South Carolina", "South Dakota",
        "Tennessee", "Texas", "Utah", "Vermont", "Virginia", "Washington", "West Virginia",
        "Wisconsin", "Wyoming"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // MARK: - Card Information Section
        Text("Card information", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        DropdownField(
            label = "Payment Method",
            options = cardProviders,
            selectedOption = cardProvider,
            onOptionSelected = { cardProvider = it }
        )

        OutlinedTextField(
            value = cardNum,
            onValueChange = { cardNum = it },
            label = { Text("Card number") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            DropdownField(
                label = "Month",
                options = (1..12).map { it.toString() },
                selectedOption = expireMonth.toString(),
                onOptionSelected = { expireMonth = it.toInt() },
                modifier = Modifier.weight(1f)
            )

            DropdownField(
                label = "Year",
                options = (currentYear..currentYear + 6).map { it.toString() },
                selectedOption = expireYear.toString(),
                onOptionSelected = { expireYear = it.toInt() },
                modifier = Modifier.weight(1f)
            )
        }

        OutlinedTextField(
            value = cvv,
            onValueChange = { if (it.length <= 3) cvv = it }, // Limits to 3 chars
            label = { Text("CVV") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        // MARK: - Billing Information Section
        Text("Billing information", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        OutlinedTextField(value = firstName, onValueChange = { firstName = it }, label = { Text("First name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = lastName, onValueChange = { lastName = it }, label = { Text("Last name") }, modifier = Modifier.fillMaxWidth())

        DropdownField(
            label = "State",
            options = stateArray,
            selectedOption = state,
            onOptionSelected = { state = it }
        )

        OutlinedTextField(value = city, onValueChange = { city = it }, label = { Text("City") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = address1, onValueChange = { address1 = it }, label = { Text("Billing address") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = address2, onValueChange = { address2 = it }, label = { Text("Billing address, line 2") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = postCode, onValueChange = { postCode = it }, label = { Text("Zip/postal code") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Phone number") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                // Submit logic here
                // val info = Payment(...)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Submit")
        }
    }
}

// Reusable dropdown helper to keep the main view clean (replaces SwiftUI Picker)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropdownField(
    label: String,
    options: List<String>,
    selectedOption: String,
    onOptionSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selectedOption,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { selectionOption ->
                DropdownMenuItem(
                    text = { Text(selectionOption) },
                    onClick = {
                        onOptionSelected(selectionOption)
                        expanded = false
                    }
                )
            }
        }
    }
}