/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */
package org.jetbrains.kotlin.backend.konan.llvm.coverage

import llvm.LLVMAddInstrProfPass
import llvm.LLVMPassManagerRef
import llvm.LLVMValueRef
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.KonanConfigKeys
import org.jetbrains.kotlin.backend.konan.reportCompilationError
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.konan.file.File
import org.jetbrains.kotlin.konan.target.CompilerOutputKind
import org.jetbrains.kotlin.konan.target.KonanTarget
import org.jetbrains.kotlin.konan.util.removeSuffixIfPresent
import org.jetbrains.kotlin.resolve.descriptorUtil.module

/**
 * "Umbrella" class of all the of the code coverage related logic.
 */
internal class CoverageManager(val context: Context) {

    private val shouldCoverProgram: Boolean =
            context.config.configuration.getBoolean(KonanConfigKeys.COVERAGE)

    private val librariesToCover: Set<String> =
            context.config.configuration.getList(KonanConfigKeys.LIBRARIES_TO_COVER)
                    .map { it.removeSuffixIfPresent(".klib") }
                    .toSet()

    private val llvmProfileFilenameGlobal = "__llvm_profile_filename"

    private val defaultOutputFilePath: String by lazy {
        "${context.config.outputFile}.profraw"
    }

    private val outputFileName: String =
            context.config.configuration.get(KonanConfigKeys.PROFRAW_PATH)
                    ?.let { File(it).absolutePath }
                    ?: defaultOutputFilePath

    val enabled: Boolean =
            shouldCoverProgram || librariesToCover.isNotEmpty()

    init {
        if (enabled && !checkRestrictions()) {
            context.reportCompilationError("Coverage is only supported for macOS executables for now.")
        }
    }

    private fun checkRestrictions(): Boolean  {
        val kind = context.config.configuration.get(KonanConfigKeys.PRODUCE)
        val isKindAllowed = kind == CompilerOutputKind.PROGRAM || kind == CompilerOutputKind.BITCODE
        val isTargetAllowed = context.config.target == KonanTarget.MACOS_X64
        return context.config.target == KonanTarget.MACOS_X64 && isKindAllowed && isTargetAllowed
    }

    private val filesRegionsInfo = mutableListOf<FileRegionInfo>()

    private fun getFunctionRegions(irFunction: IrFunction) =
            filesRegionsInfo.flatMap { it.functions }.firstOrNull { it.function == irFunction }

    private val coveredModules: Set<ModuleDescriptor> by lazy {
        val coveredUserCode = if (shouldCoverProgram) setOf(context.moduleDescriptor) else emptySet()
        val coveredLibs = context.irModules.filter { it.key in librariesToCover }.values
                .map { it.descriptor }.toSet()
        coveredLibs + coveredUserCode
    }

    private fun fileCoverageFilter(file: IrFile) =
            file.packageFragmentDescriptor.module in coveredModules

    /**
     * Walk [irModuleFragment] subtree and collect [FileRegionInfo] for files that are part of [coveredModules].
     */
    fun collectRegions(irModuleFragment: IrModuleFragment) {
        if (enabled) {
            val regions = CoverageRegionCollector(this::fileCoverageFilter).collectFunctionRegions(irModuleFragment)
            filesRegionsInfo += regions
        }
    }

    /**
     * @return [LLVMCoverageInstrumentation] instance if [irFunction] should be covered.
     */
    fun tryGetInstrumentation(irFunction: IrFunction?, callSitePlacer: (function: LLVMValueRef, args: List<LLVMValueRef>) -> Unit) =
            if (enabled && irFunction != null) {
                getFunctionRegions(irFunction)?.let { LLVMCoverageInstrumentation(context, it, callSitePlacer) }
            } else {
                null
            }

    /**
     * Add __llvm_coverage_mapping to the LLVM module.
     */
    fun writeRegionInfo() {
        if (enabled) {
            LLVMCoverageWriter(context, filesRegionsInfo).write()
        }
    }

    /**
     * Add InstrProfilingLegacyPass to the list of llvm passes
     */
    fun addLlvmPasses(passManager: LLVMPassManagerRef) {
        if (enabled) {
            LLVMAddInstrProfPass(passManager, outputFileName)
        }
    }

    /**
     * Since we performing instruction profiling before internalization and global dce
     * __llvm_profile_filename need to be added to exported symbols.
     */
    fun addExportedSymbols(): List<String> =
        if (enabled) {
             listOf(llvmProfileFilenameGlobal)
        } else {
            emptyList()
        }
}