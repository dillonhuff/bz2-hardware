package examples

import chisel3.util
import language._

import scala.collection.mutable.{HashMap, ArrayBuffer}

object JsonFieldExtractor {
  // fields must be bottom-level (i.e. non-record types), and must all be present in every input record
  // maxFieldChars can be -1 if we want specific transitions
  // returns (seqTrans, splitTrans, maxMatchId, fieldMatchIds)
  def genTransitions(fields: Array[Array[String]], maxFieldChars: Int): (Seq[BigInt], Seq[BigInt], Int, Seq[Int]) = {
    val fieldsQuoted = fields.map(f => f.map(id => "\"" + id.replace("\"", "\\\"") + "\""))

    val sequentialBranches = new ArrayBuffer[(Int, ArrayBuffer[Char])] // (field idx, branch)
    // map from (sequential branch, idx in branch) to (char, sequential branch for that char, field idx if terminal)
    val splitBranches = new HashMap[(Int, Int), ArrayBuffer[(Char, Int, Int)]]
    sequentialBranches.append((0, new ArrayBuffer[Char]))
    for ((field, fieldIdx) <- fieldsQuoted.zipWithIndex) {
      var curBranch = 0
      var curIdx = 0
      for ((id, i) <- field.zipWithIndex) {
        for ((c, j) <- id.zipWithIndex) {
          if (curIdx == sequentialBranches(curBranch)._2.length) {
            sequentialBranches(curBranch)._2.append(c)
            curIdx += 1
          } else {
            if (sequentialBranches(curBranch)._2(curIdx) == c) {
              curIdx += 1
            } else {
              val split = splitBranches.getOrElseUpdate((curBranch, curIdx), new ArrayBuffer[(Char, Int, Int)])
              var splitIdx = 0
              while (splitIdx < split.length && c != split(splitIdx)._1) {
                splitIdx += 1
              }
              if (splitIdx == split.length) {
                if (i == field.length - 1 && j == id.length - 1) {
                  split.append((c, -1, fieldIdx))
                } else {
                  split.append((c, sequentialBranches.length, -1))
                  sequentialBranches.append((fieldIdx, new ArrayBuffer[Char]))
                }
              }
              curBranch = split(splitIdx)._2
              curIdx = 0
            }
          }
        }
      }
    }

    var curStateId = 0
    val sequentialStateIds = new Array[Int](sequentialBranches.length)
    for (((_, branch), i) <- sequentialBranches.zipWithIndex) {
      sequentialStateIds(i) = curStateId
      curStateId += branch.length
    }
    if (maxFieldChars != -1) {
      curStateId = maxFieldChars
    }
    val stateBits = util.log2Ceil(curStateId + 1) // need space for curStateId as well
    assert(stateBits <= 24) // otherwise fieldMatchIds will overflow 32 bits

    val fieldMatchIds = new Array[Int](fields.length)
    val sequentialTransitions = new ArrayBuffer[BigInt]
    for (((fieldIdx, branch), i) <- sequentialBranches.zipWithIndex) {
      for ((c, j) <- branch.zipWithIndex) {
        var trans = BigInt(c.toInt) << stateBits
        if (j == branch.length - 1) {
          trans |= curStateId // field complete
          fieldMatchIds(fieldIdx) = ((sequentialStateIds(i) + j) << 8) | c.toInt
        } else {
          trans |= (sequentialStateIds(i) + j + 1) // must be the state ID after the current one
        }
        sequentialTransitions.append(trans)
      }
    }
    val splitTransitions = new ArrayBuffer[BigInt]
    for (((seqBranch, seqIdx), splits) <- splitBranches.iterator) {
      val stateForSplit = sequentialStateIds(seqBranch) + seqIdx
      for ((c, nextBranch, fieldIdx) <- splits) {
        var trans = BigInt(c.toInt) << stateBits
        if (nextBranch == -1) {
          trans |= curStateId // field complete
          fieldMatchIds(fieldIdx) = (stateForSplit << 8) | c.toInt
        } else {
          trans |= sequentialStateIds(nextBranch)
        }
        splitTransitions.append((trans << stateBits) | stateForSplit)
      }
    }
    (sequentialTransitions, splitTransitions, curStateId, fieldMatchIds)
  }

  def genConfigBits(fields: Array[Array[String]], maxFieldChars: Int): (Int, BigInt) = {
    val (seqTrans, splitTrans, maxStateId, _) = genTransitions(fields, maxFieldChars)
    val stateBits = util.log2Ceil(maxStateId + 1)
    val numBitsForConfigToken = ((2 * stateBits + 8) + 8 - 1) / 8 * 8
    var bits = BigInt(0)
    var numBits = 0
    val sentinel = (BigInt(1) << numBitsForConfigToken) - 1

    def insertConfigTokens(tokens: Seq[BigInt]): Unit = {
      for (t <- tokens ++ Array(sentinel)) {
        bits = (t << numBits) | bits
        numBits += numBitsForConfigToken
      }
    }

    insertConfigTokens(seqTrans)
    insertConfigTokens(splitTrans)
    (numBits, bits)
  }

  def genFieldMatchStrs(fields: Array[Array[String]], maxFieldChars: Int): Seq[String] = {
    val (_, _, maxStateId, fieldMatchIds) = genTransitions(fields, maxFieldChars)
    val matchStrChars = ((util.log2Ceil(maxStateId + 1) + 8) + 8 - 1) / 8
    fieldMatchIds.map(m => String.valueOf((0 until matchStrChars).map(i => ((m >> (i * 8)) & 255).toChar).toArray))
  }

  def genCircuit(seqTrans: Seq[BigInt], splitTrans: Seq[BigInt], maxMatchId: Int, maxFields: Int,
                 maxNestDepth: Int, coreId: Int): Unit = {
    object ParseState extends Enumeration {
      type ParseState = Value
      val CONF_SEQ, CONF_SPLIT, EXP_KEY, IN_KEY, EXP_COL, EXP_VAL, IN_VAL, EXP_COM = Value
    }
    import ParseState._

    val stateBits = util.log2Ceil(maxMatchId + 1)

    val inStringValue = StreamReg(1, false)
    val lastChar = StreamReg(8, ' '.toInt)
    val nestDepth = StreamReg(util.log2Ceil(maxNestDepth + 1), 0)
    val parseState = StreamReg(util.log2Ceil(ParseState.maxId), if (seqTrans == null) CONF_SEQ.id else EXP_VAL.id)
    val matchState = StreamReg(stateBits, 0)
    val seqTransRam = if (seqTrans == null) StreamBRAM(stateBits + 8, maxMatchId) else null
    val seqTransVec = if (seqTrans == null) null else StreamVectorReg(stateBits + 8, seqTrans.length, seqTrans)
    val splitTransVec =
      if (seqTrans == null) {
        StreamVectorReg(2 * stateBits + 8, maxFields,
          (0 until maxFields).map(_ => (BigInt(1) << (2 * stateBits + 8)) - 1))
      } else {
        if (splitTrans.length == 0) null else StreamVectorReg(2 * stateBits + 8, splitTrans.length, splitTrans)
      }
    val stateStack = (0 until maxNestDepth).map(i => StreamReg(stateBits, null))
    val matchStrChars = ((stateBits + 8) + 8 - 1) / 8
    val matchStrEmitCounter = StreamReg(util.log2Ceil(matchStrChars), 0)

    if (seqTrans == null) {
      val numWordsForConfigToken = ((2 * stateBits + 8) + 8 - 1) / 8
      val configToken = StreamReg(numWordsForConfigToken * 8, null)
      val configWordNum = StreamReg(util.log2Ceil(numWordsForConfigToken), 0)
      val configTokenNum = StreamReg(util.log2Ceil(maxMatchId), 0)

      swhen(parseState === CONF_SEQ.id.L || parseState === CONF_SPLIT.id.L) {
        swhen(configWordNum === (numWordsForConfigToken - 1).L) {
          val finalConfigToken = StreamInput(0) ## configToken((numWordsForConfigToken - 1) * 8 - 1, 0)
          swhen(finalConfigToken === ((BigInt(1) << (numWordsForConfigToken * 8)) - 1).L) {
            swhen(parseState === CONF_SEQ.id.L) {
              parseState := CONF_SPLIT.id.L
            }.otherwise {
              parseState := EXP_VAL.id.L
            }
            configTokenNum := 0.L
          }.otherwise {
            swhen(parseState === CONF_SEQ.id.L) {
              seqTransRam(configTokenNum) := finalConfigToken(stateBits + 7, 0)
            }.otherwise {
              splitTransVec(configTokenNum) := finalConfigToken(2 * stateBits + 7, 0)
            }
            configTokenNum := configTokenNum + 1.L
          }
          configWordNum := 0.L
        }.otherwise {
          for (i <- 0 until numWordsForConfigToken - 1) {
            swhen(configWordNum === i.L) {
              if (i == 0) {
                configToken := configToken(numWordsForConfigToken * 8 - 1, 8) ## StreamInput(0)
              } else {
                configToken := configToken(numWordsForConfigToken * 8 - 1, 8 * (i + 1)) ## StreamInput(0) ##
                  configToken(8 * i - 1, 0)
              }
            }
          }
          configWordNum := configWordNum + 1.L
        }
      }
    }

    def isWhitespace(c: StreamBits) = c === ' '.toInt.L || c === '\n'.toInt.L || c === '\t'.toInt.L

    def popStateStack = {
      matchState := stateStack(0)
      for (i <- 0 until stateStack.length - 1) {
        stateStack(i) := stateStack(i + 1)
      }
    }

    def popStateStackWithFieldSep = {
      swhen (matchState === maxMatchId.L) {
        Emit(0, ','.toInt.L)
      }
      popStateStack
    }

    def emitCurToken = {
      swhen (matchState === maxMatchId.L) {
        Emit(0, StreamInput(0))
      }
    }

    def emitMatchStateIfMatched(nextMatchState: StreamBits, output: StreamBits) = {
      swhen (nextMatchState === maxMatchId.L) {
        swhile (matchStrEmitCounter < (matchStrChars - 1).L) {
          for (i <- 0 until (matchStrChars - 1)) {
            swhen (matchStrEmitCounter === i.L) {
              Emit(0, output((i + 1) * 8 - 1, i * 8))
            }
          }
          matchStrEmitCounter := matchStrEmitCounter + 1.L
        }
        Emit(0, output(output.getWidth - 1, (matchStrChars - 1) * 8))
        matchStrEmitCounter := 0.L
      }
    }

    swhen (parseState === EXP_VAL.id.L) {
      swhen (StreamInput(0) === '{'.toInt.L) {
        parseState := EXP_KEY.id.L
        nestDepth := nestDepth + 1.L
      } .elsewhen (nestDepth =/= 0.L && !isWhitespace(StreamInput(0))) { // at nestDepth of 0 we only accept new records
        emitCurToken
        parseState := IN_VAL.id.L
        inStringValue := StreamInput(0) === '"'.toInt.L
      }
    }
    swhen (parseState === IN_VAL.id.L) {
      swhen (inStringValue.B) {
        emitCurToken
        swhen (StreamInput(0) === '"'.toInt.L && lastChar =/= '\\'.toInt.L) {
          inStringValue := false.L
        }
      } .elsewhen (StreamInput(0) =/= '}'.toInt.L) {
        swhen (StreamInput(0) === ','.toInt.L) {
          parseState := EXP_KEY.id.L
          popStateStackWithFieldSep
        } .otherwise {
          emitCurToken
        }
      }
    }
    swhen (StreamInput(0) === ','.toInt.L && parseState === EXP_COM.id.L) {
      parseState := EXP_KEY.id.L
      popStateStackWithFieldSep
    }
    swhen (StreamInput(0) === '}'.toInt.L &&
      (parseState === EXP_KEY.id.L || parseState === EXP_COM.id.L ||
        (parseState === IN_VAL.id.L && !inStringValue.B))) {
      swhen (nestDepth === 1.L) {
        Emit(0, '/'.toInt.L) // record separator
        parseState := EXP_VAL.id.L
      } .otherwise {
        parseState := EXP_COM.id.L
      }
      swhen (parseState === EXP_COM.id.L || parseState === IN_VAL.id.L) {
        swhen (nestDepth === 1.L) {
          popStateStack
        } .otherwise {
          popStateStackWithFieldSep
        }
      }
      nestDepth := nestDepth - 1.L
    }

    val enteringKey = StreamInput(0) === '"'.toInt.L && parseState === EXP_KEY.id.L
    swhen (enteringKey) {
      parseState := IN_KEY.id.L
      stateStack(0) := matchState
      for (i <- 1 until stateStack.length) {
        stateStack(i) := stateStack(i - 1)
      }
    }
    swhen ((parseState === IN_KEY.id.L || enteringKey) && matchState =/= maxMatchId.L &&
      (matchState =/= 0.L || nestDepth === 1.L)) { // only allow match to start at top level
      val selectedSeqEl = if (seqTransRam == null) seqTransVec(matchState) else seqTransRam(matchState)
      swhen (StreamInput(0) === selectedSeqEl(stateBits + 7, stateBits)) {
        emitMatchStateIfMatched(selectedSeqEl(stateBits - 1, 0), matchState##StreamInput(0))
        matchState := selectedSeqEl(stateBits - 1, 0)
      } .otherwise {
        var noSplit: StreamBool = true.L.B
        if (splitTransVec != null) {
          for (i <- 0 until splitTransVec.numEls) {
            val selectedSplitEl = splitTransVec(i.L)
            val splitMatch = matchState === selectedSplitEl(stateBits - 1, 0) &&
              StreamInput(0) === selectedSplitEl(2 * stateBits + 7, 2 * stateBits)
            noSplit = noSplit && !splitMatch
            swhen(splitMatch) {
              emitMatchStateIfMatched(selectedSplitEl(2 * stateBits - 1, stateBits), matchState##StreamInput(0))
              matchState := selectedSplitEl(2 * stateBits - 1, stateBits)
            }
          }
        }
        swhen (noSplit) {
          matchState := 0.L
        }
      }
    }
    swhen (StreamInput(0) === '"'.toInt.L && parseState === IN_KEY.id.L) {
      parseState := EXP_COL.id.L
    }
    swhen (StreamInput(0) === ':'.toInt.L && parseState === EXP_COL.id.L) {
      parseState := EXP_VAL.id.L
    }
    lastChar := StreamInput(0)
  }

  class JsonFieldExtractorGeneric(maxFieldChars: Int, maxFields: Int, maxNestDepth: Int,
                                  coreId: Int) extends ProcessingUnit(8, coreId) {
    JsonFieldExtractor.genCircuit(null, null, maxFieldChars, maxFields, maxNestDepth, coreId)
    Builder.curBuilder.compile()
  }

  class JsonFieldExtractorSpecific(fields: Array[Array[String]], maxNestDepth: Int,
                                   coreId: Int) extends ProcessingUnit(8, coreId) {
    val (seqTrans, splitTrans, maxMatchId, _) = JsonFieldExtractor.genTransitions(fields, -1)
    JsonFieldExtractor.genCircuit(seqTrans, splitTrans, maxMatchId, 0, maxNestDepth, coreId)
    Builder.curBuilder.compile()
  }
}
