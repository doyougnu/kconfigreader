package de.fosd.typechef.kconfig

import java.io.{FileWriter, File}
import scala.sys.process.Process
import de.fosd.typechef.busybox.DimacsWriter
import de.fosd.typechef.featureexpr.sat.SATFeatureExpr
import de.fosd.typechef.featureexpr.{FeatureExprFactory, FeatureExpr}
import de.fosd.typechef.featureexpr.FeatureExprFactory._


/**
 * main class
 */
object KConfigReader extends App {

    val usage = """
    Usage: kconfigreader [--dumpconf pathToDumpConfTool] [--writeNonBoolean] [--reduceConstraints] [--writeDimacs] pathToKconfigFile out
                """

    if (args.length == 0) {
        println(usage);
        sys.exit(1)
    }
    val arglist = args.toList
    type OptionMap = Map[String, String]

    def nextOption(map: OptionMap, list: List[String]): OptionMap = {
        def isSwitch(s: String) = (s(0) == '-')
        list match {
            case Nil => map
            case "--dumpconf" :: value :: tail =>
                nextOption(map ++ Map("dumpconf" -> value), tail)
            case "--writeNonBoolean" :: tail =>
                nextOption(map ++ Map("writeNonBoolean" -> "1"), tail)
            case "--writeDimacs" :: tail =>
                nextOption(map ++ Map("writeDimacs" -> "1"), tail)
            case "--writeCompletedConf" :: tail =>
                nextOption(map ++ Map("writeCompletedConf" -> "1"), tail)
            case "--reduceConstraints" :: tail =>
                nextOption(map ++ Map("reduceConstraints" -> "1"), tail)
            case string :: string2 :: Nil if !isSwitch(string) && !isSwitch(string2) => nextOption(map ++ Map("kconfigpath" -> string, "out" -> string2), Nil)
            case option :: tail => println("Unknown option " + option)
                println(map);
                sys.exit(1)
        }
    }
    val options = nextOption(Map(), arglist)


    val dumpconf = options.getOrElse("dumpconf", "../undertaker/kconfig/dumpconf")
    val kconfigPath = options("kconfigpath")
    val out = options("out")

    val kconfigFile = new File(kconfigPath)
    val rsfFile = new File(out + ".rsf")
    val modelFile = new File(out + ".model")
    val dimacsFile = new File(out + ".dimacs")
    val nonboolFile = new File(out + ".nonbool.h")
    val completedConfFile = new File(out + ".completed.h")

    assert(kconfigFile.exists(), "kconfig file does not exist")

    //creating .rsf file
    println("dumping model")
    Process(dumpconf + " %s > %s".format(kconfigFile, rsfFile)).#>(rsfFile).!

    //reading model
    println("reading model")
    val model = new RSFReader().readRSF(rsfFile)

    println("getting constraints")
    var allconstraints = model.getConstraints

    println("checking combined constraint")
    val isSat = allconstraints.reduce(_ and _).isSatisfiable()
    if (!isSat) {
        println("checking each constraint")
        assert(allconstraints.forall(_.isSatisfiable()), "extracted constraint is not satisfiable")
    }
    assert(isSat, "extracted model is not satisfiable")

    println("writing model")
    writeModel(modelFile, model)

    if (options contains "writeNonBoolean") {
        println("writing nonboolean")
        writeNonBoolean(model, nonboolFile)
    }

    if (options contains "reduceConstraints") {
        println("reducing constraints for dimacs")
        allconstraints = reduceConstraints(allconstraints)
    }

    if (options contains "writeDimacs") {
        println("writing dimacs")
        new DimacsWriter().writeAsDimacs2(allconstraints.map(_.asInstanceOf[SATFeatureExpr]), dimacsFile)
    }

    if (options contains "writeCompletedConf") {
        println("writing completed.conf")
        writeCompletedConf(model, completedConfFile)
    }

    println("done.")


    def writeModel(outputfile: File, model: KConfigModel) {
        val writer = new FileWriter(outputfile)
        var fexpr: FeatureExpr = True
        for (i <- model.items.values.toList.sortBy(_.name)) {
            writer.write("#item " + i.name + "\n")
            i.getConstraints.map(s =>
                if (!s.isTautology()) {
                    writer.write(s + "\n")
                    fexpr = fexpr and s
                })
        }
        for (i <- model.choices.values.toList.sortBy(_.name)) {
            writer.write("#choice " + i.name + "\n")
            i.getConstraints.map(s => if (!s.isTautology()) {
                writer.write(s + "\n")
                fexpr = fexpr and s
            })
        }
        writer.close()
        //        new DimacsWriter().writeAsDimacs(fexpr.asInstanceOf[SATFeatureExpr],new File(workingDir,arch+".dimacs"))
    }


    def formatExpr(s: FeatureExpr): String = if (s.isTautology()) "1"
    else
        s.asInstanceOf[SATFeatureExpr] match {
            case de.fosd.typechef.featureexpr.sat.DefinedExpr(s) =>
                assert(!(s.feature contains "="))
                "defined(CONFIG_%s)".format(s.feature)
            case de.fosd.typechef.featureexpr.sat.And(clauses) =>
                clauses.map(formatExpr).mkString("(", " && ", ")")
            case de.fosd.typechef.featureexpr.sat.Or(clauses) =>
                clauses.map(formatExpr).mkString("(", " || ", ")")
            case de.fosd.typechef.featureexpr.sat.Not(e) =>
                "!" + formatExpr(e)
        }

    def writeNonBoolean(model: KConfigModel, file: File) = {
        val writer = new FileWriter(file)

        for (item <- model.items.values; if item.isNonBoolean) {
            val defaults = item.getDefaults().filter(_._2.isSatisfiable())
            if (defaults.size == 1)
                writer.write("#define CONFIG_%s %s\n".format(item.name, defaults.keys.head))
            else for ((default, fexpr) <- defaults)
                writer.write("#if %s\n  #define CONFIG_%s %s\n#endif\n".format(formatExpr(fexpr), item.name, default))

            writer.write("\n")


        }


        writer.close()
    }

    def writeCompletedConf(model: KConfigModel, outputfile: File) = {
        val writer = new FileWriter(outputfile)

        val fm = model.getFM

        for (feature <- fm.collectDistinctFeatureObjects; if !(feature.feature contains "=")) {

            if ((fm and feature).isContradiction()) {
                writer.write("#undef CONFIG_%s\n".format(feature.feature))
                println("#undef CONFIG_" + feature.feature)
            }
            else if ((fm andNot feature).isContradiction()) {
                writer.write("#define CONFIG_%s\n".format(feature.feature))
                println("#define CONFIG_" + feature.feature)
            }

        }

        writer.close()

    }

    def reduceConstraints(fexprs: List[FeatureExpr]): List[FeatureExpr] = {

        var result: List[FeatureExpr] = Nil

        var fm: FeatureExpr = FeatureExprFactory.True
        var c = 0
        val cm = fexprs.size

        for (fexpr <- fexprs) {
            c += 1
            if (fexpr.isTautology() || (fm implies fexpr).isTautology())
                println(c + "/" + cm + " redundant: " + fexpr)
            else {
                result ::= fexpr
                fm = fm and fexpr
            }


        }

        result
    }

}