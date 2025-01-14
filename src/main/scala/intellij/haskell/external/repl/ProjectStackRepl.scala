/*
 * Copyright 2014-2019 Rik van der Kleij
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package intellij.haskell.external.repl

import java.util.concurrent.ConcurrentHashMap

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import intellij.haskell.HaskellNotificationGroup
import intellij.haskell.external.component.HaskellComponentsManager.StackComponentInfo
import intellij.haskell.external.repl.StackRepl.StackReplOutput
import intellij.haskell.util.{HaskellFileUtil, ScalaFutureUtil}

import scala.collection.JavaConverters._
import scala.concurrent.Future

case class ProjectStackRepl(project: Project, stackComponentInfo: StackComponentInfo, replTimeout: Int) extends StackRepl(project, Some(stackComponentInfo), Seq("--ghc-options", "-fobject-code"), replTimeout: Int) {

  import intellij.haskell.external.repl.ProjectStackRepl._

  val target: String = stackComponentInfo.target

  val stanzaType: StackRepl.StanzaType = stackComponentInfo.stanzaType

  val packageName: String = stackComponentInfo.packageName

  def clearLoadedModules(): Unit = {
    loadedFile = None
    loadedDependentModules.clear()
    everLoadedDependentModules.clear()
  }

  def clearLoadedModule(): Unit = {
    loadedFile = None
  }

  private case class ModuleInfo(psiFile: PsiFile, loadFailed: Boolean)

  @volatile
  private[this] var loadedFile: Option[ModuleInfo] = None

  private case class DependentModuleInfo()

  private type ModuleName = String
  private[this] val loadedDependentModules = new ConcurrentHashMap[ModuleName, DependentModuleInfo]().asScala
  private[this] val everLoadedDependentModules = new ConcurrentHashMap[ModuleName, DependentModuleInfo]().asScala

  @volatile
  private var objectCodeEnabled = true

  import scala.concurrent.ExecutionContext.Implicits.global
  import scala.concurrent.duration._

  private def isReadAccessAllowed = ApplicationManager.getApplication.isReadAccessAllowed

  def findTypeInfo(moduleName: Option[String], psiFile: PsiFile, startLineNr: Int, startColumnNr: Int, endLineNr: Int, endColumnNr: Int, expression: String): Option[StackReplOutput] = {
    val filePath = getFilePath(psiFile)

    def execute = {
      executeModuleLoadedCommand(moduleName, psiFile, s":type-at $filePath $startLineNr $startColumnNr $endLineNr $endColumnNr $expression")
    }

    if (isReadAccessAllowed) {
      ScalaFutureUtil.waitWithCheckCancelled(project, Future(execute), "Wait on :type-at in ProjectStackRepl", 30.seconds).flatten
    } else {
      execute
    }
  }

  def findLocationInfo(moduleName: Option[String], psiFile: PsiFile, startLineNr: Int, startColumnNr: Int, endLineNr: Int, endColumnNr: Int, expression: String): Option[StackReplOutput] = {
    val filePath = getFilePath(psiFile)

    def execute = {
      executeModuleLoadedCommand(moduleName, psiFile, s":loc-at $filePath $startLineNr $startColumnNr $endLineNr $endColumnNr $expression")
    }

    if (isReadAccessAllowed) {
      ScalaFutureUtil.waitWithCheckCancelled(project, Future(execute), "Wait on :loc-at in ProjectStackRepl", timeout = 30.seconds).flatten
    } else {
      execute
    }
  }

  def findInfo(psiFile: PsiFile, name: String): Option[StackReplOutput] = {
    def execute = {
      executeWithLoad(psiFile, s":info $name", mustBeByteCode = true)
    }

    if (isReadAccessAllowed) {
      ScalaFutureUtil.waitWithCheckCancelled(psiFile.getProject, Future(execute), "Wait on :info in ProjectStackRepl", 30.seconds).flatten
    } else {
      execute
    }
  }

  def isModuleLoaded(moduleName: String): Boolean = {
    everLoadedDependentModules.get(moduleName).isDefined
  }

  def isBrowseModuleLoaded(moduleName: String): Boolean = {
    loadedDependentModules.get(moduleName).isDefined
  }

  def isFileLoaded(psiFile: PsiFile): IsFileLoaded = {
    loadedFile match {
      case Some(info) if psiFile == info.psiFile && !info.loadFailed => Loaded
      case Some(info) if psiFile == info.psiFile && info.loadFailed => Failed
      case Some(_) => OtherFileIsLoaded
      case None => NoFileIsLoaded
    }
  }

  private final val FailedModulesLoaded = "Failed, "

  private final val OkModulesLoaded = "Ok, "

  private def setLoadedModules(): Unit = {
    loadedDependentModules.clear()
    execute(":show modules") match {
      case Some(output) =>
        val loadedModuleNames = output.stdoutLines.map(l => l.takeWhile(_ != ' '))
        loadedModuleNames.foreach(mn => loadedDependentModules.put(mn, DependentModuleInfo()))
        loadedModuleNames.foreach(mn => everLoadedDependentModules.put(mn, DependentModuleInfo()))
      case None => ()
    }
  }

  def load(psiFile: PsiFile, fileChanged: Boolean, mustBeByteCode: Boolean): Option[(StackReplOutput, Boolean)] = synchronized {
    val forceBytecodeLoad = if (mustBeByteCode) objectCodeEnabled else false
    val reload = if (forceBytecodeLoad || !fileChanged) {
      false
    } else {
      val loaded = isFileLoaded(psiFile)
      loaded == Loaded || loaded == Failed
    }

    val output = if (reload) {
      execute(s":reload")
    } else {
      // In case module has to be compiled to byte-code: :set -fbyte-code AND load flag *
      val byteCodeFlag = if (forceBytecodeLoad) "*" else ""
      if (forceBytecodeLoad) {
        objectCodeEnabled = false
        execute(s":set -fbyte-code")
      } else if (!objectCodeEnabled) {
        objectCodeEnabled = true
        execute(s":set -fobject-code")
      }
      val filePath = getFilePath(psiFile)
      execute(s":load $byteCodeFlag$filePath")
    }

    output match {
      case Some(o) =>
        val loadFailed = isLoadFailed(o)
        setLoadedModules()

        loadedFile = Some(ModuleInfo(psiFile, loadFailed))
        Some(o, loadFailed)
      case _ =>
        loadedDependentModules.clear()
        loadedFile = None
        None
    }
  }

  private def findLoadedModuleNames(line: String): Array[String] = {
    if (line.startsWith(OkModulesLoaded)) {
      line.replace(OkModulesLoaded, "").init.split(",").map(_.trim)
    } else if (line.startsWith(FailedModulesLoaded)) {
      line.replace(FailedModulesLoaded, "").init.split(",").map(_.trim)
    } else {
      Array()
    }
  }

  def getModuleIdentifiers(moduleName: String, psiFile: Option[PsiFile]): Option[StackReplOutput] = synchronized {
    if (psiFile.isEmpty || isBrowseModuleLoaded(moduleName) || psiFile.exists(pf => load(pf, fileChanged = false, mustBeByteCode = false).exists(_._2 == false))) {
      execute(s":browse! $moduleName")
    } else {
      HaskellNotificationGroup.logInfoEvent(project, s"Could not get module identifiers for module $moduleName because file ${psiFile.map(_.getName).getOrElse("-")} is not loaded")
      None
    }
  }

  override def restart(forceExit: Boolean): Unit = synchronized {
    if (available && !starting) {
      exit(forceExit)
      start()
    }
  }

  private def executeModuleLoadedCommand(moduleName: Option[String], psiFile: PsiFile, command: String): Option[StackReplOutput] = synchronized {
    if (moduleName.exists(isModuleLoaded)) {
      execute(command)
    } else {
      executeWithLoad(psiFile, command, mustBeByteCode = false)
    }
  }

  private def executeWithLoad(psiFile: PsiFile, command: String, moduleName: Option[String] = None, mustBeByteCode: Boolean): Option[StackReplOutput] = synchronized {
    loadedFile match {
      case Some(info) if info.psiFile == psiFile & !info.loadFailed & (if (mustBeByteCode) !objectCodeEnabled else true) => execute(command)
      case Some(info) if info.psiFile == psiFile & info.loadFailed => Some(StackReplOutput())
      case _ =>
        load(psiFile, fileChanged = false, mustBeByteCode)
        loadedFile match {
          case None => None
          case Some(info) if info.psiFile == psiFile && !info.loadFailed => execute(command)
          case _ => Some(StackReplOutput())
        }
    }
  }

  private def isLoadFailed(output: StackReplOutput): Boolean = {
    output.stdoutLines.lastOption.exists(_.contains("Failed, "))
  }

  private def getFilePath(psiFile: PsiFile): String = {
    HaskellFileUtil.getAbsolutePath(psiFile) match {
      case Some(filePath) =>
        if (filePath.contains(" ")) {
          s""""$filePath""""
        } else {
          filePath
        }
      case None => throw new IllegalStateException(s"Can not load file `${psiFile.getName}` in REPL because it exists only in memory")
    }
  }
}

object ProjectStackRepl {

  sealed trait IsFileLoaded

  case object Loaded extends IsFileLoaded

  case object Failed extends IsFileLoaded

  case object NoFileIsLoaded extends IsFileLoaded

  case object OtherFileIsLoaded extends IsFileLoaded

}
