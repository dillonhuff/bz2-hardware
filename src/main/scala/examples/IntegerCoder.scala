package examples

import chisel3.util
import language._

import scala.collection.mutable.ArrayBuffer

class IntegerCoder(wordSize: Int, batchWords: Int, coreId: Int) extends ProcessingUnit(8, coreId) {
  assert(wordSize % 8 == 0)
  assert(wordSize <= 64)
  val wordBytes = wordSize / 8
  val wordIdx = StreamReg(util.log2Ceil(batchWords + 1), 0)
  val byteIdx = StreamReg(Math.max(util.log2Ceil(wordBytes), 1), 0)
  val curWord = StreamReg(wordSize, 0)

  val maxVarIntBits = (wordSize + 7 - 1) / 7 * 8
  val bitWidths = Array(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, if (wordSize <= 32) 12 else 64, 13, 16, 20, 32)
  assert(util.isPow2(bitWidths.length))
  val maxWidth = bitWidths.max
  val idToBitWidth = StreamVectorReg(util.log2Ceil(maxWidth + 1), bitWidths.length, bitWidths.map(b => BigInt(b)))
  val bitCounts = bitWidths.map(_ => (StreamReg(util.log2Ceil(batchWords + 1), 0) /* number of words that fit */,
    StreamReg(util.log2Ceil(wordSize + 1), 0) /* max bitcount in exceptions */,
    StreamReg(util.log2Ceil(maxVarIntBits + 1), 0) /* number of bits needed for varint encoding of
    exceptions */))
  val lzToVarIntBits = StreamVectorReg(util.log2Ceil(maxVarIntBits + 1), wordSize,
    (0 until wordSize).map(lz => BigInt(((wordSize - lz) + 7 - 1) / 7 * 8)))
  object CodingState extends Enumeration {
    type CodingState = Value
    val READ_INPUT, EMIT_MAIN, EMIT_EXCEPT = Value
  }
  import CodingState._
  val curState = StreamReg(util.log2Ceil(CodingState.maxId), READ_INPUT.id)

  val outputWord = StreamReg(8, 0)
  val outputWordBits = StreamReg(3, 0)
  val wordSlice = StreamReg(util.log2Ceil(maxVarIntBits / 8), 0) // used when slicing word into chunks of 8 or 7

  object EmitMainState extends Enumeration {
    type EmitMainState = Value
    val BLOCK_SIZE, NUM_EXCEPTIONS, MAIN_VALS = Value
  }
  import EmitMainState._
  object EmitExceptState extends Enumeration {
    type EmitExceptState = Value
    val EXCEPT_SIZE, EXCEPT_POS, EXCEPT_VAL = Value
  }
  import EmitExceptState._
  val emitState = StreamReg(util.log2Ceil(Math.max(EmitMainState.maxId, EmitExceptState.maxId)), BLOCK_SIZE.id)

  val buffer = StreamBRAM(wordSize, batchWords)

  def leadingZeros(word: StreamBits): StreamBits = { // maximum return value is wordSize - 1
    assert(word.getWidth >= 4 && util.isPow2(word.getWidth))
    var curLevel = new ArrayBuffer[(StreamBool, StreamBits)] // (is all zeroes, num leading zeros)
    for (i <- 0 until word.getWidth by 4) {
      val curSlice = word(word.getWidth - i - 1, word.getWidth - i - 4)
      curLevel.append((curSlice === 0.L,
        StreamMux(curSlice(3, 3) === 1.L, 0.L, StreamMux(curSlice(2, 2) === 1.L, 1.L,
          StreamMux(curSlice(1, 1) === 1.L, 2.L, StreamMux(curSlice(0, 0) === 1.L, 3.L, 4.L))))))
    }
    while (curLevel.length > 1) {
      val nextLevel = new ArrayBuffer[(StreamBool, StreamBits)]
      for (i <- 0 until curLevel.length by 2) {
        nextLevel.append((curLevel(i)._1 && curLevel(i + 1)._1, StreamMux(curLevel(i)._1, curLevel(i)._2 +
          curLevel(i + 1)._2, curLevel(i)._2)))
      }
      curLevel = nextLevel
    }
    StreamMux(curLevel(0)._2 === word.getWidth.L, (word.getWidth - 1).L, curLevel(0)._2)
  }

  def shiftByConst(arg: StreamBits, const: Int): StreamBits = {
    if (const == 0) {
      arg
    } else {
      arg##0.L(const)
    }
  }

  def mulByConst(arg: StreamBits, const: Int): StreamBits = {
    // TODO can also do subtractions here if cheaper
    assert(const >= 1)
    var result = shiftByConst(arg, util.log2Floor(const))
    var addsSoFar = 1 << util.log2Floor(const)
    while (addsSoFar < const) {
      result = result + shiftByConst(arg, util.log2Floor(const - addsSoFar))
      addsSoFar += 1 << util.log2Floor(const - addsSoFar)
    }
    result
  }

  def mulWithConstMethod(arg: StreamBits, smallArg: StreamBits, maxSmallArg: Int): StreamBits = {
    var cur: StreamBits = 0.L
    for (i <- 1 to maxSmallArg) {
      cur = StreamMux(smallArg === i.L, mulByConst(arg, i), cur)
    }
    cur
  }

  var bestWidths = new ArrayBuffer[(StreamBits, StreamBool, StreamBits, StreamBits, StreamBits)] // (cost in bits,
  // is varint cheaper, width ID, num exceptions, exception width)
  for ((w, i) <- bitWidths.zipWithIndex) {
    val numExceptions = batchWords.L - bitCounts(i)._1
    val exceptionFixedCost = util.log2Ceil(wordSize).L /* space for exception size */ +
      mulWithConstMethod(bitCounts(i)._2, numExceptions, batchWords)
    bestWidths.append((mulWithConstMethod(w.L, bitCounts(i)._1, batchWords) /* main size */ +
      mulByConst(numExceptions, util.log2Ceil(batchWords)) /* exception positions */ +
      StreamMux(numExceptions > 0.L, 1.L, 0.L) /* var int or fixed */ +
      StreamMux(bitCounts(i)._3 < exceptionFixedCost, bitCounts(i)._3, exceptionFixedCost) /* main exception size */,
      bitCounts(i)._3 < exceptionFixedCost, i.L, numExceptions, bitCounts(i)._2))
  }
  while (bestWidths.length > 1) {
    val nextWidths = new ArrayBuffer[(StreamBits, StreamBool, StreamBits, StreamBits, StreamBits)]
    for (i <- 0 until bestWidths.length by 2) {
      val firstLess = bestWidths(i)._1 < bestWidths(i + 1)._1
      nextWidths.append((StreamMux(firstLess, bestWidths(i)._1, bestWidths(i + 1)._1),
        StreamMux(firstLess, bestWidths(i)._2, bestWidths(i + 1)._2).B,
        StreamMux(firstLess, bestWidths(i)._3, bestWidths(i + 1)._3),
        StreamMux(firstLess, bestWidths(i)._4, bestWidths(i + 1)._4),
        StreamMux(firstLess, bestWidths(i)._5, bestWidths(i + 1)._5)))
    }
    bestWidths = nextWidths
  }
  val bestWidthId = bestWidths(0)._3
  val bestWidth = idToBitWidth(bestWidthId)
  val useVarInt = bestWidths(0)._2
  val numExceptions = bestWidths(0)._4
  val exceptionWidth = bestWidths(0)._5

  def addBitsToOutputWord(bits: StreamBits, topBit: StreamBits, valid: StreamBool): Unit = { // topBit is highest valid
  // bit in bits
    assert(bits.getWidth <= 8)
    var updatedOut = bits
    for (i <- 1 until 8) { // outputWordBits = 0 means updatedOut is just bits
      updatedOut = StreamMux(outputWordBits === i.L, bits(Math.min(bits.getWidth - 1, 7 - i), 0)##outputWord(i - 1, 0),
        updatedOut)
    }
    var bitsRemainder: StreamBits = bits(bits.getWidth - 1, bits.getWidth - 1)
    for (i <- (8 - bits.getWidth + 2) until 8) { // outputWordBits in [0, 8 - bits.getWidth] is the 0 bits remaining
      // case, and outputWordBits = 8 - bits.getWidth + 1 is the 1 bit remaining case, which is covered in the first
      // value of bitsRemainder above .. so start with 8 - bits.getWidth + 2
      bitsRemainder = StreamMux(outputWordBits === i.L,
        bits(bits.getWidth - 1, 8 - i /* 8 - i - 1 is the top bit that goes into updatedOut */), bitsRemainder)
    }
    swhen (valid) {
      swhen(outputWordBits + topBit >= 7.L) {
        Emit(0, updatedOut)
        outputWord := bitsRemainder
        outputWordBits := outputWordBits + topBit - 7.L
      }.otherwise {
        outputWord := updatedOut
        outputWordBits := outputWordBits + topBit + 1.L
      }
    }
  }

  val finalInputWord = if (wordSize > 8) StreamInput(0) ## curWord(8 * (wordBytes - 1) - 1, 0) else StreamInput(0)
  val bufWord = buffer(wordIdx)
  val curLeadingZeros = leadingZeros(StreamMux(curState === READ_INPUT.id.L, finalInputWord, bufWord))
  val curBitLen = wordSize.L - curLeadingZeros
  swhen (curState === READ_INPUT.id.L) {
    for (i <- 0 until wordBytes - 1) {
      swhen(byteIdx === i.L) {
        curWord := (if (i == 0) curWord(8 * wordBytes - 1, 8) ## StreamInput(0)
        else curWord(8 * wordBytes - 1, 8 * (i + 1)) ## StreamInput(0) ## curWord(8 * i - 1, 0))
      }
    }
    swhen (byteIdx === (wordBytes - 1).L) {
      buffer(wordIdx) := finalInputWord
      byteIdx := 0.L
      swhen (wordIdx === (batchWords - 1).L) {
        wordIdx := 0.L
        curState := EMIT_MAIN.id.L
      } .otherwise {
        wordIdx := wordIdx + 1.L
      }
      for ((w, i) <- bitWidths.zipWithIndex) {
        swhen (curBitLen <= w.L) {
          bitCounts(i)._1 := bitCounts(i)._1 + 1.L
        } .otherwise {
          swhen (curBitLen > bitCounts(i)._2) {
            bitCounts(i)._2 := curBitLen
          }
          bitCounts(i)._3 := bitCounts(i)._3 + lzToVarIntBits(curLeadingZeros)
        }
      }
    }
  } .otherwise {
    swhile (wordIdx < batchWords.L) {
      var fixedByte: StreamBits = bufWord(7, 0)
      for (i <- 1 until wordBytes) {
        fixedByte = StreamMux(wordSlice === i.L, bufWord((i + 1) * 8 - 1, i * 8), fixedByte)
      }
      val fixedBitsLeft = StreamMux(emitState === EMIT_MAIN.id.L, bestWidth - wordSlice##0.L(3),
        exceptionWidth - wordSlice##0.L(3))
      val fixedBitsDone = fixedBitsLeft <= 8.L
      val fixedTopBit = StreamMux(fixedBitsDone, fixedBitsLeft - 1.L, 7.L)
      var exceptSlice: StreamBits = bufWord(6, 0)
      for (i <- 1 until maxVarIntBits / 8) {
        exceptSlice = StreamMux(wordSlice === i.L, bufWord(Math.min(wordSize - 1, (i + 1) * 7 - 1), i * 7), exceptSlice)
      }
      val exceptBitsLeft = curBitLen - mulByConst(wordSlice, 7)
      val exceptBitsDone = exceptBitsLeft <= 7.L
      val exceptByte = StreamMux(exceptBitsDone, 0.L##exceptSlice, 1.L##exceptSlice)
      val output =
        StreamMux(curState === EMIT_MAIN.id.L,
          StreamMux(emitState === BLOCK_SIZE.id.L,
            bestWidthId,
            StreamMux(emitState === NUM_EXCEPTIONS.id.L,
              numExceptions,
              fixedByte
            )
          ),
          StreamMux(emitState === EXCEPT_SIZE.id.L,
            StreamMux(useVarInt,
              1.L,
              (exceptionWidth - 1.L)##0.L // write exceptionWidth - 1 to save a bit
            ),
            StreamMux(emitState === EXCEPT_POS.id.L,
              wordIdx,
              StreamMux(useVarInt,
                exceptByte,
                fixedByte
              )
            )
          )
        )
      val outputTopBit =
        StreamMux(curState === EMIT_MAIN.id.L,
          StreamMux(emitState === BLOCK_SIZE.id.L,
            (util.log2Ceil(bitWidths.length) - 1).L,
            StreamMux(emitState === NUM_EXCEPTIONS.id.L,
              (util.log2Ceil(batchWords + 1) - 1).L, // TODO is it possible for there to be 100% exceptions? We can
              // save a bit if not
              fixedTopBit
            )
          ),
          StreamMux(emitState === EXCEPT_SIZE.id.L,
            StreamMux(useVarInt,
              0.L,
              util.log2Ceil(wordSize).L
            ),
            StreamMux(emitState === EXCEPT_POS.id.L,
              (util.log2Ceil(batchWords) - 1).L,
              StreamMux(useVarInt,
                7.L,
                fixedTopBit
              )
            )
          )
        )
      val outputValid =
        StreamMux(curState === EMIT_MAIN.id.L,
          StreamMux(emitState === MAIN_VALS.id.L,
            curBitLen <= bestWidth,
            true.L
          ),
          StreamMux(emitState === EXCEPT_SIZE.id.L,
            true.L,
            !(curBitLen <= bestWidth)
          )
        ).B
      addBitsToOutputWord(output, outputTopBit, outputValid)
      swhen (curState === EMIT_MAIN.id.L) {
        swhen (emitState === BLOCK_SIZE.id.L) {
          emitState := NUM_EXCEPTIONS.id.L
        } .elsewhen (emitState === NUM_EXCEPTIONS.id.L) {
          emitState := MAIN_VALS.id.L
        } .otherwise { // MAIN_VALS
          swhen (fixedBitsDone || !outputValid) {
            swhen (wordIdx === (batchWords - 1).L && numExceptions > 0.L) {
              wordIdx := 0.L
              curState := EMIT_EXCEPT.id.L
              emitState := EXCEPT_SIZE.id.L
            } .otherwise {
              wordIdx := wordIdx + 1.L
            }
            wordSlice := 0.L
          } .otherwise {
            wordSlice := wordSlice + 1.L
          }
        }
      } .otherwise { // EMIT_EXCEPT
        swhen (emitState === EXCEPT_SIZE.id.L) {
          emitState := EXCEPT_POS.id.L
        } .elsewhen (emitState === EXCEPT_POS.id.L) {
          swhen (outputValid) {
            emitState := EXCEPT_VAL.id.L
          } .otherwise {
            wordIdx := wordIdx + 1.L
          }
        } .otherwise { // EXCEPT_VAL
          swhen (StreamMux(useVarInt, exceptBitsDone, fixedBitsDone).B) {
            wordIdx := wordIdx + 1.L
            wordSlice := 0.L
            emitState := EXCEPT_POS.id.L
          } .otherwise {
            wordSlice := wordSlice + 1.L
          }
        }
      }
    }
    // TODO this reset logic doesn't need to happen in its own final cycle, it can happen in the last cycle of the
    // while loop
    wordIdx := 0.L
    curState := READ_INPUT.id.L
    emitState := BLOCK_SIZE.id.L
    wordSlice := 0.L
    for (t <- bitCounts) {
      t._1 := 0.L
      t._2 := 0.L
      t._3 := 0.L
    }
  }
  Builder.curBuilder.compile()
}
