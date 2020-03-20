package ammrunner

import java.io.File
import java.lang.reflect.{InvocationTargetException, Modifier}
import java.net.URLClassLoader
import java.util.Locale

import scala.annotation.tailrec

object Launch {

  def javaPath: String = {
    // No evecvp in com.oracle.svm.core.posix.headers.Unistd, so we're handling the path lookup logic ourselves
    val pathDirs = sys.env.get("PATH")
      .orElse(sys.env.find(_._1.toLowerCase(Locale.ROOT) == "path").map(_._2))
      .toSeq
      .flatMap(_.split(File.pathSeparatorChar))
    pathDirs
      .iterator
      .map(dir => new File(new File(dir), "java")) // FIXME java.exe on Windows
      .filter(_.exists())
      .toStream
      .headOption
      .map(_.getAbsolutePath)
      .getOrElse {
        ???
      }
  }

  def isNativeImage: Boolean =
    sys.props
      .get("org.graalvm.nativeimage.imagecode")
      .contains("runtime")


  private object Graalvm {
    import com.oracle.svm.core.headers.Errno
    import com.oracle.svm.core.posix.headers.Unistd
    import org.graalvm.nativeimage.c.`type`.CTypeConversion

    def execv(argc: String, argv: Seq[String]): Unit = {

      if (java.lang.Boolean.getBoolean("debug.execv"))
        System.err.println(s"Running\n$argc\n$argv\n")

      val argc0 = CTypeConversion.toCString(argc)
      val argv0 = CTypeConversion.toCStrings(argv.toArray)

      Unistd.execv(argc0.get(), argv0.get())
      val err = Errno.errno()
      val desc = CTypeConversion.toJavaString(Errno.strerror(err))
      throw new Exception(s"Error running $argc ${argv.mkString(" ")}: $desc")
    }

    def launch(classpath: Seq[File], mainClass: String, args: Seq[String]): Unit = {
      val args0 = Seq(
        "java", // not actually used
        "-cp", classpath.map(_.getAbsolutePath).mkString(File.pathSeparator),
        mainClass
      ) ++ args
      execv(javaPath, args0)
    }
  }

  private object Jvm {

    def baseLoader: ClassLoader = {

      @tailrec
      def rootLoader(cl: ClassLoader): ClassLoader = {
        val par = cl.getParent
        if (par == null)
          cl
        else
          rootLoader(par)
      }

      rootLoader(ClassLoader.getSystemClassLoader)
    }


    def launch(classpath: Seq[File], mainClass: String, args: Seq[String]): Unit = {
      val cl = new URLClassLoader(classpath.map(_.toURI.toURL).toArray, baseLoader)
      val cls = cl.loadClass(mainClass) // throws ClassNotFoundException
      val method = cls.getMethod("main", classOf[Array[String]]) // throws NoSuchMethodException
      method.setAccessible(true)
      val isStatic = Modifier.isStatic(method.getModifiers)
      assert(isStatic)

      val thread = Thread.currentThread()
      val prevLoader = thread.getContextClassLoader
      try {
        thread.setContextClassLoader(cl)
        method.invoke(null, args.toArray)
      } catch {
        case e: InvocationTargetException =>
          throw Option(e.getCause).getOrElse(e)
      } finally {
        thread.setContextClassLoader(prevLoader)
      }
    }

    def fork(
      classpath: Seq[File],
      mainClass: String,
      args: Seq[String],
      mapBuilder: ProcessBuilder => ProcessBuilder
    ): Process = {
      val cmd = Seq(
        javaPath,
        "-cp", classpath.map(_.getAbsolutePath).mkString(File.pathSeparator),
        mainClass
      ) ++ args
      val builder = mapBuilder(new ProcessBuilder(cmd: _*).inheritIO())
      builder.start()
    }
  }

  def launch(classpath: Seq[File], mainClass: String, args: Seq[String]): Unit =
    launch(classpath, mainClass, args, fork = false)

  def launch(classpath: Seq[File], mainClass: String, args: Seq[String], fork: Boolean): Unit =
    if (fork) {
      val proc = Jvm.fork(classpath, mainClass, args, identity)
      val retCode = proc.waitFor()
      if (retCode != 0)
        sys.exit(retCode)
    } else if (isNativeImage)
      Graalvm.launch(classpath, mainClass, args)
    else
      Jvm.launch(classpath, mainClass, args)

  def launchBg(
    classpath: Seq[File],
    mainClass: String,
    args: Array[String],
    mapBuilder: ProcessBuilder => ProcessBuilder
  ): Process =
    Jvm.fork(classpath, mainClass, args, mapBuilder)
}
