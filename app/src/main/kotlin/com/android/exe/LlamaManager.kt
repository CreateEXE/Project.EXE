package com.android.exe

// ──────────────────────────────────────────────────────────────────────────────
// TOMBSTONE — do not use or restore this file.
//
// LlamaManager was a dead-code wrapper that:
//   1. Called System.loadLibrary("llama") — wrong library name ("llama-android"
//      is the actual target); would throw UnsatisfiedLinkError if ever accessed.
//   2. Declared JNI signatures that don't match llama_jni.cpp.
//   3. Was never called anywhere in the app.
//
// All LLM inference goes through:
//   com.android.exe.ai.LlamaBridge  (the real JNI wrapper)
// ──────────────────────────────────────────────────────────────────────────────
