package com.nova.agent.cognitive

/**
 * NOVA's cognitive kernel — the actual AI.
 *
 * Loop: perceive → recall → route → execute → synthesize → reflect → learn
 *
 * The LLM is ONE capability among many. Different question types get
 * different processing paths. Some questions don't need the LLM at all.
 */

enum class Capability {
    AST, EXECUTE, MEMORY, CONVERSATIONS, PROJECT, PERSONA, WEB, LLM, CLARIFY
}

enum class QuestionType {
    SYNTAX, CONCEPTUAL, DEBUGGING, DESIGN, RESEARCH, CODE_REQUEST, CLARIFICATION
}

data class Perception(
    val rawInput: String,
    val hasCode: Boolean,
    val codeLanguage: String? = null,
    val codeExtracted: String? = null,
    val hasQuestion: Boolean,
    val hasError: Boolean,
    val questionType: QuestionType,
    val intentSignature: String,
    val complexity: Complexity,
)

enum class Complexity { SIMPLE, MEDIUM, COMPLEX }

data class CapabilityResult(
    val capability: Capability,
    val output: String,
    val durationMs: Long,
)

data class CognitiveResult(
    val answer: String,
    val perception: Perception,
    val capabilitiesUsed: List<Capability>,
    val capabilityResults: List<CapabilityResult>,
    val confidence: Float,
    val successScore: Float,
)

/**
 * Extract signals from the user's input deterministically (no LLM).
 */
fun perceive(input: String): Perception {
    val codeFence = Regex("""```([a-zA-Z]+)?\s*\n([\s\S]*?)```""").find(input)
    val hasCode = codeFence != null
    val codeLanguage = codeFence?.groupValues?.getOrNull(1)
    val codeExtracted = codeFence?.groupValues?.getOrNull(2)

    val hasQuestion = Regex("""\b(what|why|how|when|where|who|which|can you|could you|do you|is|are|should|would|will|explain|tell me)\b""", RegexOption.IGNORE_CASE).containsMatchIn(input)
        || input.trim().endsWith("?")

    val errorPatterns = listOf(
        Regex("""\berror\b""", RegexOption.IGNORE_CASE),
        Regex("""\bexception\b""", RegexOption.IGNORE_CASE),
        Regex("""\btraceback\b""", RegexOption.IGNORE_CASE),
        Regex("""Cannot read propert""", RegexOption.IGNORE_CASE),
        Regex("""\bTypeError\b"""), Regex("""\bReferenceError\b"""), Regex("""\bSyntaxError\b"""),
        Regex("""doesn't work""", RegexOption.IGNORE_CASE), Regex("""not working""", RegexOption.IGNORE_CASE),
    )
    val hasError = errorPatterns.any { it.containsMatchIn(input) }

    val questionType = when {
        hasCode && Regex("""what (does|is|do)|explain|walk me through|break down""", RegexOption.IGNORE_CASE).containsMatchIn(input) -> QuestionType.SYNTAX
        hasError || Regex("""why (doesn't|doesn't|don't|does not)|fix|debug|broken|wrong with""", RegexOption.IGNORE_CASE).containsMatchIn(input) -> QuestionType.DEBUGGING
        Regex("""how (should|would|could) (i|we|you) (architect|design|structure|organize)""", RegexOption.IGNORE_CASE).containsMatchIn(input)
            || Regex("""\barchitecture\b""", RegexOption.IGNORE_CASE).containsMatchIn(input) -> QuestionType.DESIGN
        Regex("""what's new|latest|recent|current|up to date|2024|2025|2026""", RegexOption.IGNORE_CASE).containsMatchIn(input) -> QuestionType.RESEARCH
        Regex("""write|create|build|implement|generate|make me""", RegexOption.IGNORE_CASE).containsMatchIn(input) -> QuestionType.CODE_REQUEST
        hasCode -> QuestionType.SYNTAX
        else -> QuestionType.CONCEPTUAL
    }

    val wordCount = input.split(Regex("""\s+""")).size
    val complexity = when {
        wordCount > 60 || (hasCode && hasQuestion && hasError) -> Complexity.COMPLEX
        wordCount > 20 || hasCode -> Complexity.MEDIUM
        else -> Complexity.SIMPLE
    }

    val intentSignature = "${questionType.name.lowercase()}:${if (hasCode) "code" else "nocode"}:${if (hasError) "err" else "noerr"}:${complexity.name.lowercase()}"

    return Perception(
        rawInput = input,
        hasCode = hasCode,
        codeLanguage = codeLanguage,
        codeExtracted = codeExtracted,
        hasQuestion = hasQuestion,
        hasError = hasError,
        questionType = questionType,
        intentSignature = intentSignature,
        complexity = complexity,
    )
}

/**
 * Route capabilities based on perception.
 * Different question types get different processing paths.
 */
fun routeCapabilities(perception: Perception): List<Capability> = when (perception.questionType) {
    QuestionType.SYNTAX -> listOf(Capability.MEMORY, Capability.PROJECT, Capability.PERSONA, Capability.LLM)
    QuestionType.CONCEPTUAL -> listOf(Capability.MEMORY, Capability.CONVERSATIONS, Capability.PROJECT, Capability.PERSONA, Capability.LLM)
    QuestionType.DEBUGGING -> listOf(Capability.MEMORY, Capability.PROJECT, Capability.PERSONA, Capability.LLM).let {
        if (perception.hasCode) listOf(Capability.EXECUTE) + it else it
    }
    QuestionType.DESIGN -> listOf(Capability.MEMORY, Capability.CONVERSATIONS, Capability.PROJECT, Capability.PERSONA, Capability.LLM)
    QuestionType.RESEARCH -> listOf(Capability.WEB, Capability.MEMORY, Capability.PROJECT, Capability.PERSONA, Capability.LLM)
    QuestionType.CODE_REQUEST -> listOf(Capability.MEMORY, Capability.PROJECT, Capability.PERSONA, Capability.LLM)
    QuestionType.CLARIFICATION -> listOf(Capability.CLARIFY)
}
