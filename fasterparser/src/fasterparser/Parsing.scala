package fasterparser

import scala.annotation.{switch, tailrec}
import language.experimental.macros
import reflect.macros.blackbox.Context
case class Logger(f: String => Unit)
object Logger {
  implicit val stdout = Logger(println)
}
object Parsing {

  type P[+T] = Parse[T]
  type P0 = Parse[Unit]

  def P[T](t: Parse[T])(implicit name: sourcecode.Name): Parse[T] = macro pMacro[T]

  def pMacro[T: c.WeakTypeTag](c: Context)
                              (t: c.Expr[Parse[T]])
                              (name: c.Expr[sourcecode.Name]): c.Expr[Parse[T]] = {

    import c.universe._
    val ctx = c.Expr[Parse[_]](q"implicitly[fasterparser.Parse[_]]")
    reify[Parse[T]]{
      val startIndex = ctx.splice.index
      t.splice match{case ctx0 =>
        if ((ctx0.traceIndex != -1 | ctx0.logDepth != 0) && !ctx0.isSuccess) {
          ctx0.failureStack = (name.splice.value -> startIndex) :: ctx0.failureStack
        }
        ctx0
      }
    }
  }

  implicit def LiteralStr(s: String)(implicit ctx: Parse[Any]): Parse[Unit] = macro literalStrMacro

  def literalStrMacro(c: Context)(s: c.Expr[String])(ctx: c.Expr[Parse[Any]]): c.Expr[Parse[Unit]] = {
    import c.universe._
    s.actualType match{
      case ConstantType(Constant(x: String)) =>
        val literalized = c.Expr[String](Literal(Constant(Util.literalize(x))))
        if (x.length == 0) reify{ ctx.splice.freshSuccess((), "") }
        else if (x.length == 1){
          val charLiteral = c.Expr[Char](Literal(Constant(x.charAt(0))))
          reify {
            ctx.splice match{ case ctx1 =>
              if (ctx1.index < ctx1.input.length && ctx1.input(ctx1.index) == charLiteral.splice){
                ctx1.freshSuccess((), literalized.splice, ctx1.index + 1)
              }else{
                ctx1.freshFailure(literalized.splice).asInstanceOf[Parse[Unit]]
              }
            }
          }
        }else{
          val xLength = c.Expr[Int](Literal(Constant(x.length)))
          val checker = c.Expr[(String, Int) => Boolean]{
            val stringSym = TermName(c.freshName("string"))
            val offsetSym = TermName(c.freshName("offset"))
            val checks = x
              .zipWithIndex
              .map { case (char, i) => q"""$stringSym.charAt($offsetSym + $i) == $char""" }
              .reduce[Tree]{case (l, r) => q"$l && $r"}

            q"($stringSym: java.lang.String, $offsetSym: scala.Int) => $checks"
          }
          reify {
            ctx.splice match{ case ctx1 =>
              if (ctx1.index + xLength.splice <= ctx1.input.length &&
                checker.splice(ctx1.input, ctx1.index)){
                ctx1.freshSuccess((), literalized.splice, ctx1.index + xLength.splice)
              }else{
                ctx1.freshFailure(literalized.splice).asInstanceOf[Parse[Unit]]
              }
            }
          }
        }
      case _ =>
        reify{
          val s1 = s.splice
          ctx.splice match{ case ctx1 =>
            if (ctx1.input.startsWith(s1, ctx1.index)) ctx1.freshSuccess((), Util.literalize(s1), ctx1.index + s1.length)
            else ctx1.freshFailure(Util.literalize(s1)).asInstanceOf[Parse[Unit]]
          }
        }
    }

  }

  def startsWithIgnoreCase(src: String, prefix: IndexedSeq[Char], offset: Int) = {
    @tailrec def rec(i: Int): Boolean = {
      if (i >= prefix.length) true
      else if(i + offset >= src.length) false
      else {
        val c1: Char = src(i + offset)
        val c2: Char = prefix(i)
        if (c1 != c2 && c1.toLower != c2.toLower) false
        else rec(i + 1)
      }
    }
    rec(0)
  }
  def IgnoreCase(s: String)(implicit ctx: Parse[Any]): Parse[Unit] = {

    if (startsWithIgnoreCase(ctx.input, s, ctx.index)) ctx.freshSuccess((), Util.literalize(s), ctx.index + s.length)
    else ctx.freshFailure(Util.literalize(s)).asInstanceOf[Parse[Unit]]
  }

  def cutMacro[T: c.WeakTypeTag](c: Context): c.Expr[Parse[T]] = {
    import c.universe._
    val lhs = c.prefix.asInstanceOf[c.Expr[EagerOps[_]]]
    reify{
      val ctx1 = lhs.splice.parse0
      if (ctx1.isSuccess) ctx1.prepareSuccess(ctx1.successValue, cut = true).asInstanceOf[Parse[T]]
      else ctx1.prepareFailure(ctx1.index)
    }
  }

  implicit def EagerOpsStr(parse0: String)(implicit ctx: Parse[Any]): fasterparser.Parsing.EagerOps[Unit] = macro eagerOpsStrMacro

  def eagerOpsStrMacro(c: Context)
                       (parse0: c.Expr[String])
                       (ctx: c.Expr[Parse[Any]]): c.Expr[fasterparser.Parsing.EagerOps[Unit]] = {
    import c.universe._
    val literal = literalStrMacro(c)(parse0)(ctx)
    reify{ fasterparser.Parsing.EagerOps[Unit](literal.splice)}
  }

  def parsedSequence0[T: c.WeakTypeTag, V: c.WeakTypeTag, R: c.WeakTypeTag]
                     (c: Context)
                     (other: c.Expr[Parse[V]], cut: Boolean)
                     (s: c.Expr[Implicits.Sequencer[T, V, R]],
                      whitespace: Option[c.Expr[Parse[Any] => Parse[Unit]]]): c.Expr[Parse[R]] = {
    import c.universe._

    val lhs = c.prefix.asInstanceOf[Expr[EagerOps[T]]]
    val cut1 = c.Expr[Boolean](if(cut) q"true" else q"false")
    val consumeWhitespace = whitespace match{
      case None => reify{(c: Parse[Any]) => true}
      case Some(ws) =>
        if (ws.tree.tpe =:= typeOf[fasterparser.NoWhitespace.noWhitespaceImplicit.type]){
          reify{(c: Parse[Any]) => true}
        }else{
          reify{(c: Parse[Any]) => ws.splice(c); c.isSuccess}
        }
    }

    reify {
      {
        lhs.splice.parse0 match{ case ctx3 =>
          if (!ctx3.isSuccess) ctx3
          else {
            val pValue = ctx3.successValue
            val pCut = ctx3.cut
            val preWsIndex = ctx3.index
            if (!consumeWhitespace.splice(ctx3)) ctx3
            else {
              val preOtherIndex = ctx3.index
              other.splice
              val postOtherIndex = ctx3.index
              val nextIndex =
                if (preOtherIndex >= postOtherIndex && postOtherIndex < ctx3.input.length) preWsIndex
                else ctx3.index
              val rhsNewCut = cut1.splice | ctx3.cut | pCut
              if (!ctx3.isSuccess) ctx3.prepareFailure(ctx3.index, cut = rhsNewCut)
              else {
                ctx3.prepareSuccess(
                  s.splice.apply(pValue.asInstanceOf[T], ctx3.successValue.asInstanceOf[V]),
                  nextIndex,
                  cut = rhsNewCut
                )
              }
            }
          }

        }
      }.asInstanceOf[Parse[R]]
    }
  }

  def parsedSequence[T: c.WeakTypeTag, V: c.WeakTypeTag, R: c.WeakTypeTag]
                    (c: Context)
                    (other: c.Expr[Parse[V]])
                    (s: c.Expr[Implicits.Sequencer[T, V, R]],
                     whitespace: c.Expr[Parse[Any] => Parse[Unit]]): c.Expr[Parse[R]] = {
    import c.universe._
    parsedSequence0[T, V, R](c)(other, false)(s, Some(whitespace))
  }

  def parsedSequenceCut[T: c.WeakTypeTag, V: c.WeakTypeTag, R: c.WeakTypeTag]
                    (c: Context)
                    (other: c.Expr[Parse[V]])
                    (s: c.Expr[Implicits.Sequencer[T, V, R]],
                     whitespace: c.Expr[Parse[Any] => Parse[Unit]]): c.Expr[Parse[R]] = {
    import c.universe._
    parsedSequence0[T, V, R](c)(other, true)(s, Some(whitespace))
  }
  def parsedSequence1[T: c.WeakTypeTag, V: c.WeakTypeTag, R: c.WeakTypeTag]
                    (c: Context)
                    (other: c.Expr[Parse[V]])
                    (s: c.Expr[Implicits.Sequencer[T, V, R]]): c.Expr[Parse[R]] = {
    import c.universe._
    parsedSequence0[T, V, R](c)(other, false)(s, None)
  }
  def parsedSequenceCut1[T: c.WeakTypeTag, V: c.WeakTypeTag, R: c.WeakTypeTag]
                    (c: Context)
                    (other: c.Expr[Parse[V]])
                    (s: c.Expr[Implicits.Sequencer[T, V, R]]): c.Expr[Parse[R]] = {
    import c.universe._
    parsedSequence0[T, V, R](c)(other, true)(s, None)
  }


  implicit class EagerOps[T](val parse0: Parse[T]) extends AnyVal{

    def ~/[V, R](other: Parse[V])
                (implicit s: Implicits.Sequencer[T, V, R],
                 whitespace: Parse[Any] => Parse[Unit]): Parse[R] = macro parsedSequenceCut[T, V, R]

    def / : Parse[T] = macro cutMacro[T]

    def ~[V, R](other:  Parse[V])
               (implicit s: Implicits.Sequencer[T, V, R],
                whitespace: Parse[Any] => Parse[Unit]): Parse[R] = macro parsedSequence[T, V, R]


    def ~~/[V, R](other: Parse[V])
                  (implicit s: Implicits.Sequencer[T, V, R]): Parse[R] = macro parsedSequenceCut1[T, V, R]


    def ~~[V, R](other: Parse[V])
                (implicit s: Implicits.Sequencer[T, V, R]): Parse[R] = macro parsedSequence1[T, V, R]

    def map[V](f: T => V): Parse[V] = macro mapMacro[T, V]

    def flatMap[V](f: T => Parse[V]): Parse[V] = macro flatMapMacro[T, V]

    def |[V >: T](other: Parse[V])(implicit ctx: Parse[Any]): Parse[V] = macro eitherMacro[T, V]

    def !(implicit ctx: Parse[Any]): Parse[String] = macro captureMacro
  }


  def mapMacro[T: c.WeakTypeTag, V: c.WeakTypeTag]
              (c: Context)
              (f: c.Expr[T => V]): c.Expr[Parse[V]] = {
    import c.universe._

    val lhs0 = c.prefix.asInstanceOf[c.Expr[EagerOps[T]]]
    reify {
      lhs0.splice.parse0 match{ case lhs =>
        if (!lhs.isSuccess) lhs.asInstanceOf[Parse[V]]
        else {
        val this2 = lhs.asInstanceOf[Parse[V]]
        this2.successValue = f.splice(this2.successValue.asInstanceOf[T])
        this2
        }
      }
    }
  }


  def flatMapMacro[T: c.WeakTypeTag, V: c.WeakTypeTag]
                  (c: Context)
                  (f: c.Expr[T => Parse[V]]): c.Expr[Parse[V]] = {
    import c.universe._

    val lhs0 = c.prefix.asInstanceOf[c.Expr[EagerOps[T]]]
    reify {
      val lhs = lhs0.splice.parse0
      if (!lhs.isSuccess) lhs.asInstanceOf[Parse[V]]
      else {
        val sCut = lhs.cut
        val res = f.splice(lhs.successValue.asInstanceOf[T])
        res.cut |= sCut
        res
      }
    }
  }

  def eitherMacro[T: c.WeakTypeTag, V >: T: c.WeakTypeTag]
                 (c: Context)
                 (other: c.Expr[Parse[V]])
                 (ctx: c.Expr[Parse[Any]]): c.Expr[Parse[V]] = {
    import c.universe._

    val lhs0 = c.prefix.asInstanceOf[c.Expr[EagerOps[T]]]
    reify {
      val ctx5 = ctx.splice.asInstanceOf[Parse[V]]
      val oldCut = ctx5.cut
      ctx5.cut = false
      val startPos = ctx5.index
      lhs0.splice
      if (ctx5.isSuccess | ctx5.cut) ctx5
      else {
        ctx5.cut = false
        ctx5.index = startPos
        other.splice
        if (ctx5.isSuccess) {
          ctx5.cut = oldCut
          ctx5
        }else if (ctx5.cut) ctx5
        else {
          val res = ctx5.prepareFailure(startPos)
          ctx5.cut = ctx5.cut | oldCut
          ctx5.failureStack = Nil
          if (ctx5.traceIndex == -1) ctx5.shortFailureMsg = () => "???"
          res
        }
      }

    }
  }

  def captureMacro(c: Context)
                  (ctx: c.Expr[Parse[Any]]): c.Expr[Parse[String]] = {
    import c.universe._

    val lhs0 = c.prefix.asInstanceOf[c.Expr[EagerOps[_]]]

    reify {
      val ctx6 = ctx.splice
      val startPos = ctx6.index
      lhs0.splice
      if (!ctx6.isSuccess) ctx6.asInstanceOf[Parse[String]]
      else ctx6.prepareSuccess(ctx6.input.substring(startPos, ctx6.index))
    }
  }

  implicit def ByNameOpsStr(parse0: String)(implicit ctx: Parse[Any]): fasterparser.Parsing.ByNameOps[Unit] = macro byNameOpsStrMacro

  def byNameOpsStrMacro(c: Context)
                       (parse0: c.Expr[String])
                       (ctx: c.Expr[Parse[Any]]): c.Expr[fasterparser.Parsing.ByNameOps[Unit]] = {
    import c.universe._
    val literal = literalStrMacro(c)(parse0)(ctx)
    reify{ new fasterparser.Parsing.ByNameOps[Unit](() => literal.splice)}
  }

  implicit def ByNameOps[T](parse0: => Parse[T]) = new ByNameOps(() => parse0)

  class ByNameOps[T](val parse0: () => Parse[T]) extends AnyVal{

    def repX[V](implicit repeater: Implicits.Repeater[T, V], ctx: Parse[Any]): Parse[V] = {

      val acc = repeater.initial
      def end(successIndex: Int, index: Int, count: Int) = {
        ctx.prepareSuccess(repeater.result(acc), successIndex)
      }
      @tailrec def rec(startIndex: Int, count: Int, precut: Boolean): Parse[V] = {
        ctx.cut = precut
        parse0()
        if (!ctx.isSuccess) {
          if (ctx.cut | precut) ctx.asInstanceOf[Parse[V]]
          else end(startIndex, startIndex, count)
        }else {
          val beforeSepIndex = ctx.index
          repeater.accumulate(ctx.successValue.asInstanceOf[T], acc)
          val nextCount = count + 1
          ctx.cut = false
          rec(beforeSepIndex, nextCount, false)
        }
      }
      ctx.isSuccess = true
      rec(ctx.index, 0, false)
    }
    def repX[V](min: Int = 0,
                sep: => Parse[_] = null,
                max: Int = Int.MaxValue,
                exactly: Int = -1)
               (implicit repeater: Implicits.Repeater[T, V],
                ctx: Parse[Any]): Parse[V] = {

      val acc = repeater.initial
      val actualMin = if(exactly == -1) min else exactly
      val actualMax = if(exactly == -1) max else exactly
      def end(successIndex: Int, index: Int, count: Int) = {
        if (count < actualMin) ctx.prepareFailure(index)
        else ctx.prepareSuccess(repeater.result(acc), successIndex)
      }
      @tailrec def rec(startIndex: Int, count: Int, precut: Boolean): Parse[V] = {
        ctx.cut = precut
        if (count == 0 && actualMax == 0) ctx.prepareSuccess(repeater.result(acc), startIndex)
        else {
          parse0()
          if (!ctx.isSuccess) {
            if (ctx.cut | precut) ctx.asInstanceOf[Parse[V]]
            else end(startIndex, startIndex, count)
          }else {
            val beforeSepIndex = ctx.index
            repeater.accumulate(ctx.successValue.asInstanceOf[T], acc)
            val nextCount = count + 1
            if (nextCount == actualMax) end(beforeSepIndex, beforeSepIndex, nextCount)
            else {
              ctx.cut = false
              if (sep == null) rec(beforeSepIndex, nextCount, false)
              else {
                if (ctx.isSuccess) rec(beforeSepIndex, nextCount, ctx.cut)
                else if (ctx.cut) ctx.prepareFailure(beforeSepIndex)
                else end(beforeSepIndex, beforeSepIndex, nextCount)
              }
            }
          }
        }
      }
      ctx.isSuccess = true
      rec(ctx.index, 0, false)
    }
    def repX[V](min: Int,
                sep: => Parse[_])
               (implicit repeater: Implicits.Repeater[T, V],
                ctx: Parse[Any]): Parse[V] = {

      val acc = repeater.initial
      def end(successIndex: Int, index: Int, count: Int) = {
        if (count < min) ctx.prepareFailure(index)
        else ctx.prepareSuccess(repeater.result(acc), successIndex)
      }
      @tailrec def rec(startIndex: Int, count: Int, precut: Boolean): Parse[V] = {
        ctx.cut = precut
        parse0()
        if (!ctx.isSuccess) {
          if (ctx.cut | precut) ctx.asInstanceOf[Parse[V]]
          else end(startIndex, startIndex, count)
        }else {
          val beforeSepIndex = ctx.index
          repeater.accumulate(ctx.successValue.asInstanceOf[T], acc)
          val nextCount = count + 1
          ctx.cut = false
          if (sep == null) rec(beforeSepIndex, nextCount, false)
          else {
            if (ctx.isSuccess) rec(beforeSepIndex, nextCount, ctx.cut)
            else if (ctx.cut) ctx.prepareFailure(beforeSepIndex)
            else end(beforeSepIndex, beforeSepIndex, nextCount)
          }
        }
      }
      ctx.isSuccess = true
      rec(ctx.index, 0, false)
    }
    def repX[V](min: Int)
               (implicit repeater: Implicits.Repeater[T, V],
                ctx: Parse[Any]): Parse[V] = {

      val acc = repeater.initial
      def end(successIndex: Int, index: Int, count: Int) = {
        if (count < min) ctx.prepareFailure(index)
        else ctx.prepareSuccess(repeater.result(acc), successIndex)
      }
      @tailrec def rec(startIndex: Int, count: Int, precut: Boolean): Parse[V] = {
        ctx.cut = precut
        parse0()
        if (!ctx.isSuccess) {
          if (ctx.cut | precut) ctx.asInstanceOf[Parse[V]]
          else end(startIndex, startIndex, count)
        }else {
          val beforeSepIndex = ctx.index
          repeater.accumulate(ctx.successValue.asInstanceOf[T], acc)
          val nextCount = count + 1
          ctx.cut = false
          rec(beforeSepIndex, nextCount, false)
        }
      }
      ctx.isSuccess = true
      rec(ctx.index, 0, false)
    }
    def rep[V](implicit repeater: Implicits.Repeater[T, V],
               whitespace: Parse[_] => Parse[Unit],
               ctx: Parse[Any]): Parse[V] = {


      val acc = repeater.initial
      def end(successIndex: Int, index: Int, count: Int) = {
        ctx.prepareSuccess(repeater.result(acc), successIndex)
      }
      @tailrec def rec(startIndex: Int, count: Int, precut: Boolean): Parse[V] = {
        ctx.cut = precut
        parse0()
        if (!ctx.isSuccess){
          if (ctx.cut | precut) ctx.asInstanceOf[Parse[V]]
          else end(startIndex, startIndex, count)
        }else{
          val beforeSepIndex = ctx.index
          repeater.accumulate(ctx.successValue.asInstanceOf[T], acc)
          val nextCount = count + 1
          if (whitespace ne NoWhitespace.noWhitespaceImplicit) whitespace(ctx)
          ctx.cut = false
          rec(beforeSepIndex, nextCount, false)
        }
      }

      rec(ctx.index, 0, false)
    }
    def rep[V](min: Int = 0,
               sep: => Parse[_] = null,
               max: Int = Int.MaxValue,
               exactly: Int = -1)
              (implicit repeater: Implicits.Repeater[T, V],
               whitespace: Parse[_] => Parse[Unit],
               ctx: Parse[Any]): Parse[V] = {


      val acc = repeater.initial
      val actualMin = if(exactly == -1) min else exactly
      val actualMax = if(exactly == -1) max else exactly
      def end(successIndex: Int, index: Int, count: Int) = {
        if (count < actualMin) ctx.prepareFailure(index)
        else ctx.prepareSuccess(repeater.result(acc), successIndex)
      }
      @tailrec def rec(startIndex: Int, count: Int, precut: Boolean): Parse[V] = {
        ctx.cut = precut
        if (count == 0 && actualMax == 0) ctx.prepareSuccess(repeater.result(acc), startIndex)
        else {
          parse0()
          if (!ctx.isSuccess){
            if (ctx.cut | precut) ctx.asInstanceOf[Parse[V]]
            else end(startIndex, startIndex, count)
          }else{
            val beforeSepIndex = ctx.index
            repeater.accumulate(ctx.successValue.asInstanceOf[T], acc)
            val nextCount = count + 1
            if (nextCount == actualMax) end(beforeSepIndex, beforeSepIndex, nextCount)
            else {
              if (whitespace ne NoWhitespace.noWhitespaceImplicit) whitespace(ctx)
              ctx.cut = false
              if (sep == null) rec(beforeSepIndex, nextCount, false)
              else if (ctx.isSuccess) {
                val sepCut = ctx.cut
                if (whitespace ne NoWhitespace.noWhitespaceImplicit) whitespace(ctx)
                rec(beforeSepIndex, nextCount, sepCut)
              }
              else if (ctx.cut) ctx.prepareFailure(beforeSepIndex)
              else end(beforeSepIndex, beforeSepIndex, nextCount)
            }
          }
        }
      }
      rec(ctx.index, 0, false)
    }
    def rep[V](min: Int,
               sep: => Parse[_])
              (implicit repeater: Implicits.Repeater[T, V],
               whitespace: Parse[_] => Parse[Unit],
               ctx: Parse[Any]): Parse[V] = {


      val acc = repeater.initial
      def end(successIndex: Int, index: Int, count: Int) = {
        if (count < min) ctx.prepareFailure(index)
        else ctx.prepareSuccess(repeater.result(acc), successIndex)
      }
      @tailrec def rec(startIndex: Int, count: Int, precut: Boolean): Parse[V] = {
        ctx.cut = precut
        parse0()
        if (!ctx.isSuccess){
          if (ctx.cut | precut) ctx.asInstanceOf[Parse[V]]
          else end(startIndex, startIndex, count)
        }else{
          val beforeSepIndex = ctx.index
          repeater.accumulate(ctx.successValue.asInstanceOf[T], acc)
          val nextCount = count + 1
          if (whitespace ne NoWhitespace.noWhitespaceImplicit) whitespace(ctx)
          ctx.cut = false
          if (sep == null) rec(beforeSepIndex, nextCount, false)
          else if (ctx.isSuccess) {
            val sepCut = ctx.cut
            if (whitespace ne NoWhitespace.noWhitespaceImplicit) whitespace(ctx)
            rec(beforeSepIndex, nextCount, sepCut)
          }
          else if (ctx.cut) ctx.prepareFailure(beforeSepIndex)
          else end(beforeSepIndex, beforeSepIndex, nextCount)
        }
      }
      rec(ctx.index, 0, false)
    }
    def rep[V](min: Int)
              (implicit repeater: Implicits.Repeater[T, V],
               whitespace: Parse[_] => Parse[Unit],
               ctx: Parse[Any]): Parse[V] = {


      val acc = repeater.initial
      def end(successIndex: Int, index: Int, count: Int) = {
        if (count < min) ctx.prepareFailure(index)
        else ctx.prepareSuccess(repeater.result(acc), successIndex)
      }
      @tailrec def rec(startIndex: Int, count: Int, precut: Boolean): Parse[V] = {
        ctx.cut = precut
        parse0()
        if (!ctx.isSuccess){
          if (ctx.cut | precut) ctx.asInstanceOf[Parse[V]]
          else end(startIndex, startIndex, count)
        }else{
          val beforeSepIndex = ctx.index
          repeater.accumulate(ctx.successValue.asInstanceOf[T], acc)
          val nextCount = count + 1
          if (whitespace ne NoWhitespace.noWhitespaceImplicit) whitespace(ctx)
          ctx.cut = false
          rec(beforeSepIndex, nextCount, false)
        }
      }
      rec(ctx.index, 0, false)
    }

    def opaque(msg: String)(implicit ctx: Parse[Any]) = {
      val oldFailures = ctx.failureAggregate
      val oldIndex = ctx.index
      val res = parse0()
      if (ctx.traceIndex != -1){
        ctx.failureAggregate = oldFailures
        if (ctx.traceIndex == oldIndex && !res.isSuccess) {
          ctx.failureStack = Nil
          ctx.aggregateFailure(msg)
        }
      } else if (!res.isSuccess){
        ctx.failureStack = Nil
        ctx.shortFailureMsg = () => msg
        ctx.index = oldIndex
      }
      res
    }


    def unary_!(implicit ctx: Parse[Any]) : Parse[Unit] = {
      val startPos = ctx.index
      val startCut = ctx.cut
      val startFailures = ctx.failureAggregate
      parse0()
      val res =
        if (!ctx.isSuccess) ctx.freshSuccess((), null, startPos)
        else {
          val res = ctx.prepareFailure(startPos)
          // Do not aggregate failures inside the !(...) expression,
          // since those failures are desired to make the parse succeed!
          ctx.failureAggregate = startFailures
          res
        }
      res.cut = startCut
      res
    }

    def ?[V](implicit optioner: Implicits.Optioner[T, V], ctx: Parse[Any]): Parse[V] = {
      val startPos = ctx.index
      val startCut = ctx.cut
      ctx.cut = false
      parse0()
      if (ctx.isSuccess) {
        val res = ctx.prepareSuccess(optioner.some(ctx.successValue.asInstanceOf[T]))
        res.cut = startCut
        res
      }
      else if (ctx.cut) ctx.asInstanceOf[Parse[V]]
      else {
        val res = ctx.freshSuccess(optioner.none, null, startPos)
        res.cut = startCut
        res
      }
    }

    def filter(f: T => Boolean)(implicit ctx: Parse[Any]): Parse[T] = {
      parse0()
      if (!ctx.isSuccess) ctx.asInstanceOf[Parse[T]]
      else if (f(ctx.successValue.asInstanceOf[T])) ctx.asInstanceOf[Parse[T]]
      else {
        val prevCut = ctx.cut
        val res = ctx.freshFailure("filter").asInstanceOf[Parse[T]]
        res.cut = prevCut
        res
      }
    }
  }
  implicit def LogByNameOps[T](parse0: => Parse[T])(implicit ctx: Parse[_]) = {
    new LogByNameOps(() => parse0)(ctx)
  }

  class LogByNameOps[T](parse0: () => Parse[T])(ctx: Parse[_]) {
    def log(implicit name: sourcecode.Name, logger: Logger = Logger.stdout): Parse[T] = {

      val msg = name.value
      val output = logger.f
      val indent = "  " * ctx.logDepth

      output(s"$indent+$msg:${Util.prettyIndex(ctx.input, ctx.index)}${if (ctx.cut) ", cut" else ""}")
      val depth = ctx.logDepth
      ctx.logDepth += 1
      val startIndex = ctx.index
      parse0()
      ctx.logDepth = depth
      val strRes = if (ctx.isSuccess){
        val prettyIndex = Util.prettyIndex(ctx.input, ctx.index)
        s"Success($prettyIndex${if (ctx.cut) ", cut" else ""})"
      } else{
        val trace = Result.Failure.formatStack(
          ctx.input,
          (Option(ctx.shortFailureMsg).fold("")(_()) -> ctx.index) :: ctx.failureStack.reverse
        )
        val trailing = Result.Failure.formatTrailing(ctx.input, startIndex)
        s"Failure($trace ...$trailing${if (ctx.cut) ", cut" else ""})"
      }
      output(s"$indent-$msg:${Util.prettyIndex(ctx.input, startIndex)}:$strRes")
      //        output(s"$indent-$msg:${repr.prettyIndex(cfg.input, index)}:$strRes")
      ctx.asInstanceOf[Parse[T]]
    }

  }

  def &(parse: => Parse[_])(implicit ctx: Parse[_]): Parse[Unit] = {

    val startPos = ctx.index
    val startCut = ctx.cut
    parse
    val res =
      if (ctx.isSuccess) ctx.prepareSuccess((), startPos)
      else ctx.asInstanceOf[Parse[Unit]]
    res.cut = startCut
    res

  }

  def End(implicit ctx: Parse[_]): Parse[Unit] = {
    if (ctx.index == ctx.input.length) ctx.freshSuccess((), "end-of-input")
    else ctx.freshFailure("end-of-input").asInstanceOf[Parse[Unit]]

  }

  def Start(implicit ctx: Parse[_]): Parse[Unit] = {
    if (ctx.index == 0) ctx.freshSuccess((), "start-of-input")
    else ctx.freshFailure("start-of-input").asInstanceOf[Parse[Unit]]

  }

  def Pass(implicit ctx: Parse[_]): Parse[Unit] = ctx.freshSuccess((), null)
  def NoTrace[T](p: => Parse[T])(implicit ctx: Parse[_]): Parse[T] = {
    val preMsg = ctx.failureAggregate
    val res = p
    if (ctx.traceIndex != -1) ctx.failureAggregate = preMsg
    res
  }
  def Pass[T](v: T)(implicit ctx: Parse[_]): Parse[T] = ctx.freshSuccess(v, null)

  def Fail(implicit ctx: Parse[_]): Parse[Nothing] = ctx.freshFailure("failure").asInstanceOf[Parse[Nothing]]

  def Index(implicit ctx: Parse[_]): Parse[Int] = ctx.freshSuccess(ctx.index, null)

  def AnyChar(implicit ctx: Parse[_]): Parse[Unit] = {
    if (!(ctx.index < ctx.input.length)) ctx.freshFailure("any-character").asInstanceOf[Parse[Unit]]
    else ctx.freshSuccess((), "any-character", ctx.index + 1)
  }
  def SingleChar(implicit ctx: Parse[_]): Parse[Char] = {
    if (!(ctx.index < ctx.input.length)) ctx.freshFailure("any-character").asInstanceOf[Parse[Char]]
    else ctx.freshSuccess(ctx.input(ctx.index), "any-character", ctx.index + 1)
  }
  def CharPred(p: Char => Boolean)(implicit ctx: Parse[_]): Parse[Unit] = {
    if (!(ctx.index < ctx.input.length && p(ctx.input(ctx.index)))) ctx.freshFailure("character-predicate").asInstanceOf[Parse[Unit]]
    else ctx.freshSuccess((), "character-predicate", ctx.index + 1)
  }
  def CharIn(s: String*)(implicit ctx: Parse[_]): Parse[Unit] = macro charInMacro
  def parseCharCls(c: Context)(char: c.Expr[Char], ss: Seq[String]) = {
    import c.universe._

    val snippets = for(s <- ss) yield{
      val output = collection.mutable.Buffer.empty[Either[Char, (Char, Char)]]
      var i = 0
      while(i < s.length){
        s(i) match{
          case '\\' =>
            i += 1
            output.append(Left(s(i)))
          case '-' =>
            i += 1
            val Left(last) = output.remove(output.length - 1)
            output.append(Right((last, s(i))))
          case c => output.append(Left(c))
        }
        i += 1
      }

      (
        output.collect{case Left(char) => cq"$char => true"},
        output.collect{case Right((l, h)) => q"$l <= charIn && charIn <= $h"}
      )
    }

    val (literals, ranges) = snippets.unzip
    c.Expr[Boolean](q"""$char match{
      case ..${literals.flatten}
      case charIn => ${ranges.flatten.reduceOption{ (l, r) => q"$l || $r"}.getOrElse(q"false")}
    }""")
  }

  def charInMacro(c: Context)
                 (s: c.Expr[String]*)
                 (ctx: c.Expr[Parse[Any]]): c.Expr[Parse[Unit]] = {
    import c.universe._

    val literals = s.map(_.actualType match{
      case ConstantType(Constant(x: String)) => x
      case _ => c.abort(c.enclosingPosition, "Function can only accept constant singleton type")
    })

    val parsed = parseCharCls(c)(reify(ctx.splice.input(ctx.splice.index)), literals)
    val bracketed = c.Expr[String](Literal(Constant(literals.map("[" + _ + "]").mkString)))
    reify {
      if (!(ctx.splice.index < ctx.splice.input.length)) {
        ctx.splice.freshFailure(bracketed.splice).asInstanceOf[Parse[Unit]]
      } else parsed.splice match {
        case true => ctx.splice.freshSuccess((), bracketed.splice, ctx.splice.index + 1)
        case false => ctx.splice.freshFailure(bracketed.splice).asInstanceOf[Parse[Unit]]
      }
    }
  }
  def CharsWhileIn(s: String)
                 (implicit ctx: Parse[_]): Parse[Unit] = macro charsWhileInMacro1
  def CharsWhileIn(s: String, min: Int)
                 (implicit ctx: Parse[_]): Parse[Unit] = macro charsWhileInMacro

  def charsWhileInMacro1(c: Context)
                        (s: c.Expr[String])
                        (ctx: c.Expr[Parse[Any]]): c.Expr[Parse[Unit]] = {
    import c.universe._
    charsWhileInMacro(c)(s, reify(1))(ctx)
  }
  def charsWhileInMacro(c: Context)
                        (s: c.Expr[String], min: c.Expr[Int])
                        (ctx: c.Expr[Parse[Any]]): c.Expr[Parse[Unit]] = {
    import c.universe._

    val literal = s.actualType match{
      case ConstantType(Constant(x: String)) => x
      case _ => c.abort(c.enclosingPosition, "Function can only accept constant singleton type")
    }

    val bracketed = c.Expr[String](Literal(Constant("[" + literal + "]")))

    val ctx1 = TermName(c.freshName("ctx"))
    val index = TermName(c.freshName("index"))
    val input = TermName(c.freshName("input"))
    val inputLength = TermName(c.freshName("inputLength"))
    val start = TermName(c.freshName("start"))
    val res = q"""
      val $ctx1 = $ctx
      var $index = $ctx1.index
      val $input = $ctx1.input
      val $inputLength = $input.length


      val $start = $index
      while(
        $index < $inputLength &&
        ${parseCharCls(c)(c.Expr[Char](q"$input($index)"), Seq(literal))}
      ) $index += 1
      if ($index - $start >= $min) $ctx1.freshSuccess((), "chars-while-in(" + $bracketed+ ", " + $min + ")", index = $index)
      else {
        if ($ctx.index == $ctx1.traceIndex) $ctx1.aggregateFailure($bracketed)
        $ctx1.shortFailureMsg = () => $bracketed
        $ctx1.isSuccess = false
        $ctx1.asInstanceOf[Parse[Unit]]
      }
    """
    c.Expr[Parse[Unit]](res)
  }
  def CharsWhile(p: Char => Boolean, min: Int = 1)(implicit ctx: Parse[_]): Parse[Unit] = {
    var index = ctx.index
    val input = ctx.input
    val inputLength = input.length


    val start = index
    while(index < inputLength && p(input(index))) index += 1
    if (index - start >= min) ctx.freshSuccess((), s"chars-while($min)", index = index)
    else {
      if (ctx.index == ctx.traceIndex) ctx.aggregateFailure(s"chars-while($min)")
      ctx.isSuccess = false
      ctx.shortFailureMsg = () => s"chars-while($min)"
      ctx.asInstanceOf[Parse[Unit]]
    }
  }

  def NoCut[T](parse: => Parse[T])(implicit ctx: Parse[_]): Parse[T] = {
    val cut = ctx.cut
    val res = parse
    res.cut = cut
    res
  }


  def StringIn(s: String*)(implicit ctx: Parse[_]): Parse[Unit] = macro stringInMacro
  def StringInIgnoreCase(s: String*)(implicit ctx: Parse[_]): Parse[Unit] = macro stringInIgnoreCaseMacro

  def stringInMacro(c: Context)
                 (s: c.Expr[String]*)
                 (ctx: c.Expr[Parse[Any]]): c.Expr[Parse[Unit]] = {
    stringInMacro0(c)(false, s:_*)(ctx)
  }
  def stringInIgnoreCaseMacro(c: Context)
                 (s: c.Expr[String]*)
                 (ctx: c.Expr[Parse[Any]]): c.Expr[Parse[Unit]] = {
    stringInMacro0(c)(true, s:_*)(ctx)
  }
    def stringInMacro0(c: Context)
                      (ignoreCase: Boolean, s: c.Expr[String]*)
                      (ctx: c.Expr[Parse[Any]]): c.Expr[Parse[Unit]] = {
    import c.universe._

    val literals = s.map(_.actualType match{
      case ConstantType(Constant(x: String)) => x
      case _ => c.abort(c.enclosingPosition, "Function can only accept constant singleton type")
    })
    val trie = new TrieNode(if (ignoreCase) literals.map(_.toLowerCase) else literals)
    val ctx1 = TermName(c.freshName("ctx"))
    val output = TermName(c.freshName("output"))
    val index = TermName(c.freshName("index"))
    val input = TermName(c.freshName("input"))
    val inputLength = TermName(c.freshName("inputLength"))
    val n = TermName(c.freshName("n"))
    def rec(depth: Int, t: TrieNode): c.Expr[Unit] = {
      val charAt = if (ignoreCase) q"$input.charAt($n).toLower" else q"$input.charAt($n)"
      val children = if (t.children.size == 0) q"()"
      else if (t.children.size > 1){
        q"""
        $charAt match {
          case ..${t.children.map { case (k, v) => cq"$k => ${rec(depth + 1, v)}" }}
          case _ =>
        }
        """
      }else{
        q"""
        if($charAt == ${t.children.keys.head}) ${rec(depth + 1, t.children.values.head)}
        """
      }
      val run = q"""
         val $n = $index + $depth
         ..${if (t.word) Seq(q"$output = $n") else Nil}
         if ($n < $inputLength) $children
      """
      val wrap = TermName(c.freshName("wrap"))
      c.Expr[Unit](
        // Best effort attempt to break up the huge methods that tend to be
        // created by StringsIn. Not exact, but hopefully will result in
        // multiple smaller methods being created
        if (!t.break) run
        else q"""
          def $wrap() = $run
          $wrap()
        """
      )
    }

    val bracketed = "StringIn(" + literals.map(Util.literalize(_)).mkString(", ") + ")"

    val res = q"""
      val $ctx1 = $ctx
      val $index = $ctx1.index
      val $input = $ctx1.input
      val $inputLength = $input.length

      var $output: Int = -1
      ${rec(0, trie)}
      if ($output != -1) $ctx1.freshSuccess((), $bracketed, index = $output)
      else {
        $ctx1.isSuccess = false
        if ($ctx1.traceIndex == -1){
          $ctx1.shortFailureMsg = () => $bracketed
        }else if ($ctx1.traceIndex == $index){
          $ctx1.aggregateFailure($bracketed)
        }
        $ctx1.asInstanceOf[Parse[Unit]]

      }
    """

    c.Expr[Parse[Unit]](res)

  }
  final class TrieNode(strings: Seq[String], ignoreCase: Boolean = false) {

    val ignoreCaseStrings = if (ignoreCase) strings.map(_.map(_.toLower)) else strings
    val children = ignoreCaseStrings.filter(!_.isEmpty)
      .groupBy(_(0))
      .map { case (k,ss) => k -> new TrieNode(ss.map(_.tail), ignoreCase) }

    val rawSize = children.values.map(_.size).sum + children.size

    val break = rawSize >= 8
    val size: Int = if (break) 1 else rawSize
    val word: Boolean = strings.exists(_.isEmpty) || children.isEmpty
  }
}

