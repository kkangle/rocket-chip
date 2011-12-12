package Top {

import Chisel._
import Node._;
import Constants._;
import scala.math._;

class StoreMaskGen extends Component {
  val io = new Bundle {
    val typ   = Bits(3, 'input)
    val addr  = Bits(3, 'input)
    val wmask = Bits(8, 'output)
  }
  
  val mask = Wire { Bits(width = io.wmask.width) }
  switch (io.typ(1,0))
  {
    is (MT_B) { mask <== Bits(  1,1) <<     io.addr(2,0).toUFix }
    is (MT_H) { mask <== Bits(  3,2) << Cat(io.addr(2,1), Bits(0,1)).toUFix }
    is (MT_W) { mask <== Bits( 15,4) << Cat(io.addr(2,2), Bits(0,2)).toUFix }
    otherwise { mask <== Bits(255,8) } // MT_D
  }
  io.wmask := mask
}

class StoreDataGen extends Component {
  val io = new Bundle {
    val typ  = Bits(3, 'input)
    val addr = Bits(3, 'input)
    val din  = Bits(64, 'input)
    val dout = Bits(64, 'output)
  }

  val data = Wire { Bits(width = io.din.width) }
  switch (io.typ(1,0))
  {
    is (MT_B) { data <== Fill(8, io.din( 7,0)) }
    is (MT_H) { data <== Fill(4, io.din(15,0)) }
    is (MT_W) { data <== Fill(2, io.din(31,0)) }
    otherwise { data <== io.din } // MT_D
  }
  io.dout := data
}

class LoadDataGen extends Component {
  val io = new Bundle {
    val typ  = Bits(3, 'input)
    val addr = Bits(3, 'input)
    val din  = Bits(64, 'input)
    val dout = Bits(64, 'output)
    val dout_subword = Bits(64, 'output)
  }

  val shifted = io.din >> Cat(io.addr(2), Bits(0, 5)).toUFix
  val extended = Wire { Bits(width = io.din.width) }
  switch (io.typ)
  {
    is(MT_W)  { extended <== Cat(Fill(32, shifted(31)), shifted(31,0)) }
    is(MT_WU) { extended <== Cat(Bits(0, 32),           shifted(31,0)) }
    otherwise { extended <== shifted }
  }

  val shifted_subword = Reg(extended) >> Cat(Reg(io.addr(1,0)), Bits(0, 3)).toUFix
  val extended_subword = Wire { Bits(width = io.din.width) }
  switch (Reg(io.typ))
  {
    is (MT_B)  { extended_subword <== Cat(Fill(56, shifted_subword( 7)), shifted_subword( 7, 0)) }
    is (MT_BU) { extended_subword <== Cat(Bits(0, 56),                   shifted_subword( 7, 0)) }
    is (MT_H)  { extended_subword <== Cat(Fill(48, shifted_subword(15)), shifted_subword(15, 0)) }
    is (MT_HU) { extended_subword <== Cat(Bits(0, 48),                   shifted_subword(15, 0)) }
    otherwise  { extended_subword <== shifted_subword }
  }

  io.dout := extended
  io.dout_subword := extended_subword
}

class RPQEntry extends Bundle {
  val offset = Bits(width = OFFSET_BITS)
  val cmd    = Bits(width = 4)
  val typ    = Bits(width = 3)
  val sdq_id = UFix(width = log2up(NSDQ))
  val tag    = Bits(width = CPU_TAG_BITS)
}

class Replay extends Bundle {
  val idx    = Bits(width = IDX_BITS)
  val offset = Bits(width = OFFSET_BITS)
  val cmd    = Bits(width = 4)
  val typ    = Bits(width = 3)
  val sdq_id = UFix(width = log2up(NSDQ))
  val tag    = Bits(width = CPU_TAG_BITS)
}

class DataReq extends Bundle {
  val idx    = Bits(width = IDX_BITS)
  val offset = Bits(width = IDX_BITS)
  val cmd    = Bits(width = 4)
  val typ    = Bits(width = 3)
  val data = Bits(width = CPU_DATA_BITS)
}

class DataArrayReq extends Bundle {
  val idx    = Bits(width = IDX_BITS)
  val offset = Bits(width = log2up(REFILL_CYCLES))
  val rw     = Bool()
  val wmask  = Bits(width = MEM_DATA_BITS/8)
  val data   = Bits(width = MEM_DATA_BITS)
}

class MemReq extends Bundle {
  val rw   = Bool()
  val addr = UFix(width = PPN_BITS+IDX_BITS)
  val tag  = Bits(width = DMEM_TAG_BITS)
}

class WritebackReq extends Bundle {
  val ppn = Bits(width = PPN_BITS)
  val idx = Bits(width = IDX_BITS)
}

class MetaData extends Bundle {
  val valid = Bool()
  val dirty = Bool()
  val tag = Bits(width = PPN_BITS)
}

class MetaArrayReq extends Bundle {
  val idx  = Bits(width = IDX_BITS)
  val rw  = Bool()
  val data = new MetaData()
}

class MSHR(id: Int) extends Component {
  val io = new Bundle {
    val req_pri_val    = Bool('input)
    val req_pri_rdy    = Bool('output)
    val req_sec_val    = Bool('input)
    val req_sec_rdy    = Bool('output)
    val req_ppn        = Bits(PPN_BITS, 'input)
    val req_idx        = Bits(IDX_BITS, 'input)
    val req_offset     = Bits(width = OFFSET_BITS)
    val req_cmd        = Bits(width = 4)
    val req_type       = Bits(width = 3)
    val req_sdq_id     = UFix(width = log2up(NSDQ))
    val req_tag        = Bits(CPU_TAG_BITS, 'input)

    val idx_match      = Bool('output)
    val idx            = Bits(IDX_BITS, 'output)
    val tag            = Bits(PPN_BITS, 'output)

    val mem_resp_val = Bool('input)
    val mem_req  = (new ioDecoupled) { new MemReq()  }.flip
    val meta_req = (new ioDecoupled) { new MetaArrayReq() }.flip
    val replay   = (new ioDecoupled) { new Replay()   }.flip
  }

  val valid = Reg(resetVal = Bool(false))
  val dirty = Reg { Bool() }
  val requested = Reg { Bool() }
  val refilled = Reg { Bool() }
  val ppn = Reg { Bits() }
  val idx_ = Reg { Bits() }

  val req_load = (io.req_cmd === M_XRD) || (io.req_cmd === M_PFR)
  val req_use_rpq = (io.req_cmd != M_PFR) && (io.req_cmd != M_PFW)
  val next_dirty = io.req_pri_val && io.req_pri_rdy && !req_load || io.req_sec_val && io.req_sec_rdy && (!req_load || dirty)
  val sec_rdy = io.idx_match && !refilled && (dirty || !requested || req_load)

  val rpq = (new queueSimplePF(NRPQ)) { new RPQEntry() }
  rpq.io.q_reset := Bool(false)
  rpq.io.enq.valid := (io.req_pri_val && io.req_pri_rdy || io.req_sec_val && sec_rdy) && req_use_rpq
  rpq.io.enq.bits.offset := io.req_offset
  rpq.io.enq.bits.cmd := io.req_cmd
  rpq.io.enq.bits.typ := io.req_type
  rpq.io.enq.bits.sdq_id := io.req_sdq_id
  rpq.io.enq.bits.tag := io.req_tag
  rpq.io.deq.ready := io.replay.ready && refilled

  when (io.req_pri_val && io.req_pri_rdy) {
    valid <== Bool(true)
    requested <== Bool(false)
    refilled <== Bool(false)
    ppn <== io.req_ppn
    idx_ <== io.req_idx
  }
  when (io.mem_req.valid && io.mem_req.ready) {
    requested <== Bool(true)
  }
  when (io.mem_resp_val) {
    refilled <== Bool(true)
  }
  when (io.meta_req.valid && io.meta_req.ready) {
    valid <== Bool(false)
  }
  dirty <== next_dirty

  io.idx_match := valid && (idx_ === io.req_idx)
  io.idx := idx_
  io.tag := ppn
  io.req_pri_rdy := !valid
  io.req_sec_rdy := sec_rdy && rpq.io.enq.ready

  io.meta_req.valid := valid && refilled && !rpq.io.deq.valid
  io.meta_req.bits.rw := Bool(true)
  io.meta_req.bits.idx := idx_
  io.meta_req.bits.data.valid := Bool(true)
  io.meta_req.bits.data.dirty := dirty
  io.meta_req.bits.data.tag := ppn

  io.mem_req.valid := valid && !requested
  //io.mem_req.bits.itm := next_dirty
  io.mem_req.bits.rw := Bool(false)
  io.mem_req.bits.addr := Cat(ppn, idx_).toUFix
  io.mem_req.bits.tag := Bits(id)

  io.replay.valid := rpq.io.deq.valid && refilled
  io.replay.bits.idx := idx_
  io.replay.bits.tag := rpq.io.deq.bits.tag
  io.replay.bits.offset := rpq.io.deq.bits.offset
  io.replay.bits.cmd := rpq.io.deq.bits.cmd
  io.replay.bits.typ := rpq.io.deq.bits.typ
  io.replay.bits.sdq_id := rpq.io.deq.bits.sdq_id
}

class MSHRFile extends Component {
  val io = new Bundle {
    val req_val    = Bool('input)
    val req_rdy    = Bool('output)
    val req_ppn    = Bits(PPN_BITS, 'input)
    val req_idx    = Bits(IDX_BITS, 'input)
    val req_offset = Bits(OFFSET_BITS, 'input)
    val req_cmd    = Bits(4, 'input)
    val req_type   = Bits(3, 'input)
    val req_data   = Bits(CPU_DATA_BITS, 'input)
    val req_tag    = Bits(CPU_TAG_BITS, 'input)
    val req_sdq_id = UFix(log2up(NSDQ), 'input)

    val mem_resp_val = Bool('input)
    val mem_resp_tag = Bits(DMEM_TAG_BITS, 'input)
    val mem_resp_idx = Bits(IDX_BITS, 'output)

    val mem_req  = (new ioDecoupled) { new MemReq()  }.flip()
    val meta_req = (new ioDecoupled) { new MetaArrayReq() }.flip()
    val replay   = (new ioDecoupled) { new Replay()   }.flip()
  }

  val idx_match = Wire { Bool() }
  val pri_rdy = Wire { Bool() }
  val sec_rdy = Wire { Bool() }

  val tag_mux = new Mux1H(NMSHR, PPN_BITS)
  val mem_resp_idx_mux = new Mux1H(NMSHR, IDX_BITS)
  val meta_req_arb = (new Arbiter(NMSHR)) { new MetaArrayReq() }
  val mem_req_arb = (new Arbiter(NMSHR)) { new MemReq() }
  val replay_arb = (new Arbiter(NMSHR)) { new Replay() }

  val alloc_arb = (new Arbiter(NMSHR)) { Bool() }
  alloc_arb.io.out.ready := io.req_val && !idx_match

  val tag_match = tag_mux.io.out === io.req_ppn

  for (i <- 0 to NMSHR-1) {
    val mshr = new MSHR(i)

    tag_mux.io.sel(i) := mshr.io.idx_match
    tag_mux.io.in(i) := mshr.io.tag

    alloc_arb.io.in(i).valid := mshr.io.req_pri_rdy
    mshr.io.req_pri_val := alloc_arb.io.in(i).ready

    mshr.io.req_sec_val := io.req_val && tag_match
    mshr.io.req_ppn := io.req_ppn
    mshr.io.req_tag := io.req_tag
    mshr.io.req_idx := io.req_idx
    mshr.io.req_offset := io.req_offset
    mshr.io.req_cmd := io.req_cmd
    mshr.io.req_type := io.req_type
    mshr.io.req_sdq_id := io.req_sdq_id

    mshr.io.meta_req <> meta_req_arb.io.in(i)
    mshr.io.mem_req <> mem_req_arb.io.in(i)
    mshr.io.replay <> replay_arb.io.in(i)

    val mem_resp_val = io.mem_resp_val && (UFix(i) === io.mem_resp_tag)
    mshr.io.mem_resp_val := mem_resp_val
    mem_resp_idx_mux.io.sel(i) := mem_resp_val
    mem_resp_idx_mux.io.in(i) := mshr.io.idx

    when (mshr.io.req_pri_rdy) { pri_rdy <== Bool(true) }
    when (mshr.io.req_sec_rdy) { sec_rdy <== Bool(true) }
    when (mshr.io.idx_match) { idx_match <== Bool(true) }
  }
  pri_rdy <== Bool(false)
  sec_rdy <== Bool(false)
  idx_match <== Bool(false)

  meta_req_arb.io.out ^^ io.meta_req
  mem_req_arb.io.out ^^ io.mem_req
  replay_arb.io.out ^^ io.replay

  io.req_rdy := Mux(idx_match, tag_match && sec_rdy, pri_rdy)
  io.mem_resp_idx := mem_resp_idx_mux.io.out
}

class ReplayUnit extends Component {
  val io = new Bundle {
    val sdq_enq    = (new ioDecoupled) { Bits(width = CPU_DATA_BITS) }
    val sdq_id     = UFix(log2up(NSDQ), 'output)
    val replay     = (new ioDecoupled) { new Replay() }
    val data_req   = (new ioDecoupled) { new DataReq() }.flip()
    val cpu_resp_val = Bool('output)
    val cpu_resp_tag = Bits(CPU_TAG_BITS, 'output)
  }

  val sdq_val = Reg(resetVal = UFix(0, NSDQ))
  val sdq_allocator = new priorityEncoder(NSDQ)
  sdq_allocator.io.in := ~sdq_val
  val sdq_alloc_id = sdq_allocator.io.out.toUFix

  val replay_retry = Wire { Bool() }
  val replay_val = Reg(io.replay.valid || replay_retry, resetVal = Bool(false))
  replay_retry <== replay_val && !io.data_req.ready

  val rp = Reg { new Replay() }
  when (io.replay.valid && io.replay.ready) { rp <== io.replay.bits }

  val rp_amo = rp.cmd(3).toBool
  val rp_store = (rp.cmd === M_XWR)
  val rp_load = (rp.cmd === M_XRD)
  val rp_write = rp_store || rp_amo
  val rp_read = rp_load || rp_amo

  val sdq_ren_new = io.replay.valid && (io.replay.bits.cmd != M_XRD)
  val sdq_ren_retry = replay_retry && rp_write
  val sdq_ren = sdq_ren_new || sdq_ren_retry
  val sdq_wen = io.sdq_enq.valid && io.sdq_enq.ready
  val sdq_addr = Mux(sdq_ren_retry, rp.sdq_id, Mux(sdq_ren_new, io.replay.bits.sdq_id, sdq_alloc_id))

  val sdq = Mem4(NSDQ, io.sdq_enq.bits)
  sdq.setReadLatency(0)
  sdq.setTarget('inst)
  val sdq_dout = sdq.rw(sdq_addr, io.sdq_enq.bits, sdq_wen, cs = sdq_ren || sdq_wen)

  val sdq_free = replay_val && !replay_retry && rp_write
  sdq_val <== sdq_val & ~(sdq_free.toUFix << rp.sdq_id) | (sdq_wen.toUFix << sdq_alloc_id)

  io.sdq_enq.ready := (~sdq_val != UFix(0)) && !sdq_ren
  io.sdq_id := sdq_alloc_id

  io.replay.ready := !replay_retry

  io.data_req.valid := replay_val
  io.data_req.bits.idx := rp.idx
  io.data_req.bits.offset := rp.offset
  io.data_req.bits.cmd := rp.cmd
  io.data_req.bits.typ := rp.typ
  io.data_req.bits.data := sdq_dout

  io.cpu_resp_val := Reg(replay_val && !replay_retry && rp_read, resetVal = Bool(false))
  io.cpu_resp_tag := Reg(rp.tag)
}

class WritebackUnit extends Component {
  val io = new Bundle {
    val req    = (new ioDecoupled) { new WritebackReq() }
    val data_req  = (new ioDecoupled) { new DataArrayReq() }.flip()
    val data_resp = Bits(MEM_DATA_BITS, 'input)
    val mem_req   = (new ioDecoupled) { new MemReq() }.flip()
    val mem_req_data = Bits(MEM_DATA_BITS, 'output)
  }

  val wbq = (new queueSimplePF(REFILL_CYCLES)) { Bits(width = MEM_DATA_BITS) }
  val valid = Reg(resetVal = Bool(false))
  val cnt = Reg() { UFix(width = log2up(REFILL_CYCLES+1)) }
  val addr = Reg() { new WritebackReq() }

  wbq.io.enq.valid := valid && Reg(io.data_req.valid && io.data_req.ready)
  wbq.io.enq.bits := io.data_resp
  wbq.io.deq.ready := io.mem_req.ready && (cnt === UFix(REFILL_CYCLES))

  when (io.req.valid && io.req.ready) { valid <== Bool(true); cnt <== UFix(0); addr <== io.req.bits }
  when (io.data_req.valid && io.data_req.ready) { cnt <== cnt + UFix(1) }
  when ((cnt === UFix(REFILL_CYCLES)) && !wbq.io.deq.valid) { valid <== Bool(false) }

  io.req.ready := !valid
  io.data_req.valid := valid && wbq.io.enq.ready
  io.data_req.bits.idx := addr.idx
  io.data_req.bits.offset := cnt
  io.data_req.bits.rw := Bool(false)
  io.data_req.bits.wmask := Bits(0)
  io.data_req.bits.data := Bits(0)
  io.mem_req.valid := wbq.io.deq.valid && (cnt === UFix(REFILL_CYCLES))
  io.mem_req.bits.rw := Bool(true)
  io.mem_req.bits.addr := Cat(addr.ppn, addr.idx).toUFix
  io.mem_req.bits.tag := Bits(0)
  io.mem_req_data := wbq.io.deq.bits
}

class FlushUnit(lines: Int) extends Component {
  val io = new Bundle {
    val req  = (new ioDecoupled) { Bits(width = CPU_TAG_BITS) }
    val resp = (new ioDecoupled) { Bits(width = CPU_TAG_BITS) }.flip()
    val meta_req   = (new ioDecoupled) { new MetaArrayReq() }.flip()
    val meta_resp  = (new MetaData).asInput()
    val wb_req = (new ioDecoupled) { new WritebackReq() }.flip()
  }
  
  val s_reset :: s_ready :: s_meta_read :: s_meta_wait :: s_meta_write :: s_done :: Nil = Enum(6) { UFix() }
  val state = Reg(resetVal = s_reset)
  val tag = Reg() { Bits() }
  val cnt = Reg(resetVal = UFix(0, log2up(lines)))
  val next_cnt = cnt + UFix(1)

  switch (state) {
    is(s_reset) { when (io.meta_req.ready) { state <== Mux(~cnt === UFix(0), s_ready, s_reset); cnt <== next_cnt } }
    is(s_ready) { when (io.req.valid) { state <== s_meta_read; tag <== io.req.bits } }
    is(s_meta_read) { when (io.meta_req.ready) { state <== s_meta_wait } }
    is(s_meta_wait) { state <== Mux(io.meta_resp.valid && io.meta_resp.dirty && !io.wb_req.ready, s_meta_read, s_meta_write) }
    is(s_meta_write) { when (io.meta_req.ready) { state <== Mux(~cnt === UFix(0), s_done, s_meta_read); cnt <== next_cnt } }
    is(s_done) { when (io.resp.ready) { state <== s_ready } }
  }

  io.req.ready := state === s_ready
  io.resp.valid := state === s_done
  io.resp.bits := tag
  io.meta_req.valid := (state === s_meta_read) || (state === s_meta_write) || (state === s_reset)
  io.meta_req.bits.idx := cnt
  io.meta_req.bits.rw := (state === s_meta_write) || (state === s_reset)
  io.meta_req.bits.data.valid := Bool(false)
  io.meta_req.bits.data.dirty := Bool(false)
  io.meta_req.bits.data.tag := UFix(0)
  io.wb_req.valid := state === s_meta_wait
  io.meta_resp ^^ io.wb_req.bits
}

class MetaDataArray(lines: Int) extends Component {
  val io = new Bundle {
    val req  = (new ioDecoupled) { new MetaArrayReq() }
    val resp = (new MetaData).asOutput()

    val state_req = (new ioDecoupled) { new MetaArrayReq() }
  }

  val vd_array = Mem4(lines, Bits(width = 2))
  vd_array.setReadLatency(0)
  val vd_wdata1 = Cat(io.req.bits.data.valid, io.req.bits.data.dirty)
  val vd_rdata1 = vd_array.rw(io.req.bits.idx, vd_wdata1, io.req.valid && io.req.bits.rw)
  val vd_wdata2 = Cat(io.state_req.bits.data.valid, io.req.bits.data.dirty)
  vd_array.write(io.state_req.bits.idx, vd_wdata2, io.state_req.valid && io.state_req.bits.rw)

  val tag_array = Mem4(lines, io.resp.tag)
  tag_array.setReadLatency(0)
  tag_array.setTarget('inst)
  val tag_rdata = tag_array.rw(io.req.bits.idx, io.req.bits.data.tag, io.req.valid && io.req.bits.rw, cs = io.req.valid)

  io.resp.valid := vd_rdata1(1).toBool
  io.resp.dirty := vd_rdata1(0).toBool
  io.resp.tag   := tag_rdata
  io.req.ready  := Bool(true)
}

class DataArray(lines: Int) extends Component {
  val io = new Bundle {
    val req  = (new ioDecoupled) { new DataArrayReq() }
    val resp = Bits(width = MEM_DATA_BITS, dir = 'output)
  }

  val wmask = FillInterleaved(8, io.req.bits.wmask)

  val array = Mem4(lines*REFILL_CYCLES, io.resp)
  array.setReadLatency(0)
  array.setTarget('inst)
  val addr = Cat(io.req.bits.idx, io.req.bits.offset)
  val rdata = array.rw(addr, io.req.bits.data, io.req.valid && io.req.bits.rw, wmask, cs = io.req.valid)
  io.resp := rdata
  io.req.ready := Bool(true)
}

class rocketNBDCacheAMOALU extends Component {
  val io = new Bundle {
    val cmd    = Bits(4, 'input)
    val wmask  = Bits(64/8, 'input)
    val lhs    = UFix(64, 'input)
    val rhs    = UFix(64, 'input)
    val result = UFix(64, 'output)
  }
  
  val signed = (io.cmd === M_XA_MIN) || (io.cmd === M_XA_MAX)
  val sub = (io.cmd === M_XA_MIN) || (io.cmd === M_XA_MINU) || (io.cmd === M_XA_MAX) || (io.cmd === M_XA_MAXU)
  val min = (io.cmd === M_XA_MIN) || (io.cmd === M_XA_MINU)

  val addsub_rhs = Mux(sub, ~io.rhs, io.rhs)
  val adder_lhs = Cat(io.lhs(63,32), io.wmask(3) & io.lhs(31), io.lhs(30,0)).toUFix;
  val adder_rhs = Cat(addsub_rhs(63,32), io.wmask(3) & addsub_rhs(31), addsub_rhs(30,0)).toUFix;
  val adder_out = adder_lhs + adder_rhs + sub.toUFix

  val cmp_lhs  = Mux(io.wmask(7), io.lhs(63), io.lhs(31))
  val cmp_rhs  = Mux(io.wmask(7), io.rhs(63), io.rhs(31))
  val cmp_diff = Mux(io.wmask(7), adder_out(63), adder_out(31))
  val less = Mux(cmp_lhs === cmp_rhs, cmp_diff, Mux(signed, cmp_lhs, cmp_rhs))
  val cmp_out = Mux(min === less, io.lhs, io.rhs)

  val alu_out = Wire { UFix(width = io.result.width) };
  switch (io.cmd) {
    is (M_XA_ADD)  { alu_out <== adder_out }
    is (M_XA_SWAP) { alu_out <== io.rhs }
    is (M_XA_AND)  { alu_out <== io.lhs & io.rhs }
    is (M_XA_OR)   { alu_out <== io.lhs | io.rhs }
  }
  alu_out <== cmp_out

  io.result := alu_out
}

// XXX broken for CPU_DATA_BITS != 64
class AMOUnit extends Component {
  val io = new Bundle {
    val req      = (new ioDecoupled) { new DataReq() }
    val lhs      = Bits(width = CPU_DATA_BITS)
    val rhs      = Bits(width = CPU_DATA_BITS)
    val wmask    = Bits(width = CPU_DATA_BITS/8, dir = 'input)
    val data_req = (new ioDecoupled) { new DataReq() }.flip()
  }

  val valid    = Reg(resetVal = Bool(false))
  val r_cmd    = Reg() { Bits() }
  val r_offset = Reg() { Bits() }
  val r_type   = Reg() { Bits() }
  val r_idx    = Reg() { Bits() }
  val r_lhs    = Reg() { Bits() }
  val r_rhs    = Reg() { Bits() }
  val r_wmask  = Reg() { Bits() }
  when (io.req.valid && io.req.ready) {
    valid <== Bool(true);
    r_idx <== io.req.bits.idx
    r_lhs <== io.lhs;
    r_rhs <== io.rhs;
    r_cmd <== io.req.bits.cmd;
    r_type <== io.req.bits.typ;
    r_offset <== io.req.bits.offset;
    r_wmask <== io.wmask
  }
  when (io.data_req.valid && io.data_req.ready) {
    valid <== Bool(false)
  }

  val alu = new rocketNBDCacheAMOALU
  alu.io.cmd := r_cmd
  alu.io.wmask := r_wmask
  alu.io.lhs := r_lhs
  alu.io.rhs := r_rhs

  io.req.ready := !valid
  io.data_req.valid := valid
  io.data_req.bits.idx := r_idx
  io.data_req.bits.cmd := r_cmd
  io.data_req.bits.typ := r_type
  io.data_req.bits.offset := r_offset
  io.data_req.bits.data := alu.io.result
}

class HellaCache(lines: Int) extends Component {
  val io = new ioDCacheDM();
  
  val addrbits = PADDR_BITS;
  val indexbits = log2up(lines);
  val offsetbits = OFFSET_BITS;
  val tagmsb    = PADDR_BITS-1;
  val taglsb    = indexbits+offsetbits;
  val tagbits   = tagmsb-taglsb+1;
  val indexmsb  = taglsb-1;
  val indexlsb  = offsetbits;
  val offsetmsb = indexlsb-1;
  val offsetlsb = log2up(CPU_DATA_BITS/8);
  val ramindexlsb = log2up(MEM_DATA_BITS/8);
  
  val early_nack       = Reg { Bool() }
  val r_cpu_req_val_   = Reg(io.cpu.req_val, resetVal = Bool(false))
  val r_cpu_req_val    = r_cpu_req_val_ && !io.cpu.req_kill && !early_nack
  val r_cpu_req_idx    = Reg() { Bits() }
  val r_cpu_req_cmd    = Reg() { Bits() }
  val r_cpu_req_type   = Reg() { Bits() }
  val r_cpu_req_tag    = Reg() { Bits() }
  val r_cpu_req_data   = Reg() { Bits() }
  
  val r2_cpu_req_val   = Reg(r_cpu_req_val, resetVal = Bool(false))
  val r2_cpu_req_ppn    = Reg(io.cpu.req_ppn)
  val r2_cpu_req_idx    = Reg(r_cpu_req_idx)
  val r2_cpu_req_cmd    = Reg(r_cpu_req_cmd)
  val r2_cpu_req_type   = Reg(r_cpu_req_type)
  val r2_cpu_req_tag    = Reg(r_cpu_req_tag)

  val p_store_valid    = Reg(resetVal = Bool(false))
  val p_store_data     = Reg() { Bits() }
  val p_store_idx      = Reg() { Bits() }
  val p_store_type     = Reg() { Bits() }

  val store_data_wide  = Wire { Bits(width = MEM_DATA_BITS) }
  val store_wmask_wide = Wire { Bits(width = MEM_DATA_BITS) }

  val req_store   = (io.cpu.req_cmd === M_XWR)
  val req_load    = (io.cpu.req_cmd === M_XRD) || (io.cpu.req_cmd === M_PRD)
  val req_flush   = (io.cpu.req_cmd === M_FLA)
  val req_amo     = io.cpu.req_cmd(3).toBool
  val req_read    = req_load || req_amo
  val req_write   = req_store || req_amo
  val r_req_load  = (r_cpu_req_cmd === M_XRD)
  val r_req_store = (r_cpu_req_cmd === M_XWR)
  val r_req_flush = (r_cpu_req_cmd === M_FLA)
  val r_req_amo   = r_cpu_req_cmd(3).toBool
  val r_req_read  = r_req_load || r_req_amo
  val r_req_write = r_req_store || r_req_amo
  val r2_req_load  = (r2_cpu_req_cmd === M_XRD)
  val r2_req_store = (r2_cpu_req_cmd === M_XWR)
  val r2_req_amo   = r2_cpu_req_cmd(3).toBool
  val r2_req_write = r2_req_store || r2_req_amo

  val nack_wb    = Wire { Bool() }
  val nack_mshr  = Wire { Bool() }
  val nack_sdq   = Wire { Bool() }
  
  when (io.cpu.req_val) {
    r_cpu_req_idx  <== io.cpu.req_idx
    r_cpu_req_cmd  <== Mux(req_load, M_XRD, io.cpu.req_cmd)
    r_cpu_req_type <== io.cpu.req_type
    r_cpu_req_tag  <== io.cpu.req_tag
    when (req_write) {
      r_cpu_req_data <== io.cpu.req_data
    }
  }

  // tags
  val meta = new MetaDataArray(lines)
  val meta_arb = (new Arbiter(3)) { new MetaArrayReq() }
  meta_arb.io.out <> meta.io.req

  // data
  val data = new DataArray(lines)
  val data_arb = (new Arbiter(5)) { new DataArrayReq() }
  data_arb.io.out <> data.io.req

  // writeback unit
  val wb = new WritebackUnit
  val mem_arb = (new Arbiter(2)) { new MemReq() }
  val wb_arb = (new Arbiter(2)) { new WritebackReq() }
  wb_arb.io.out <> wb.io.req
  wb.io.data_req <> data_arb.io.in(1)
  wb.io.data_resp <> data.io.resp
  wb.io.mem_req <> mem_arb.io.in(0)

  // reset and flush unit
  val flusher = new FlushUnit(lines)
  flusher.io.req.valid := r_cpu_req_val && r_req_flush
  flusher.io.wb_req <> wb_arb.io.in(0)
  flusher.io.meta_req <> meta_arb.io.in(0)
  flusher.io.meta_resp <> meta.io.resp

  // cpu tag check
  meta_arb.io.in(2).valid := io.cpu.req_val
  meta_arb.io.in(2).bits.idx := io.cpu.req_idx(indexmsb,indexlsb)
  meta_arb.io.in(2).bits.rw := Bool(false)
  meta_arb.io.in(2).bits.data.valid := Bool(false) // don't care
  meta_arb.io.in(2).bits.data.dirty := Bool(false) // don't care
  meta_arb.io.in(2).bits.data.tag := UFix(0)       // don't care
  val early_tag_nack = !meta_arb.io.in(2).ready
  val tag_match = meta.io.resp.valid && (meta.io.resp.tag === io.cpu.req_ppn)
  val tag_hit  = r_cpu_req_val &&  tag_match
  val tag_miss = r_cpu_req_val && !tag_match

  // refill counter
  val rr_count = Reg(resetVal = UFix(0, log2up(REFILL_CYCLES)))
  val rr_count_next = rr_count + UFix(1)
  when (io.mem.resp_val) { rr_count <== rr_count_next }

  // refill response
  val block_during_refill = !io.mem.resp_val && (rr_count != UFix(0))
  data_arb.io.in(0).valid := io.mem.resp_val || block_during_refill
  data_arb.io.in(0).bits.offset := rr_count
  data_arb.io.in(0).bits.rw := !block_during_refill
  data_arb.io.in(0).bits.wmask := ~UFix(0, MEM_DATA_BITS/8)
  data_arb.io.in(0).bits.data := io.mem.resp_data

  // writeback
  val wb_rdy = wb_arb.io.in(1).ready
  wb_arb.io.in(1).valid := r_cpu_req_val && !tag_match && meta.io.resp.dirty
  wb_arb.io.in(1).bits.ppn := meta.io.resp.tag
  wb_arb.io.in(1).bits.idx := r_cpu_req_idx(indexmsb,indexlsb)

  // load hits
  data_arb.io.in(4).bits.offset := io.cpu.req_idx(offsetmsb,ramindexlsb)
  data_arb.io.in(4).bits.idx := io.cpu.req_idx(indexmsb,indexlsb)
  data_arb.io.in(4).bits.rw := Bool(false)
  data_arb.io.in(4).bits.wmask := UFix(0) // don't care
  data_arb.io.in(4).bits.data := io.mem.resp_data // don't care
  data_arb.io.in(4).valid := io.cpu.req_val && req_read
  val early_load_nack = req_read && !data_arb.io.in(4).ready

  // store hits.
  // we nack new stores if a pending store can't retire for some reason.
  // we drain a pending store if the CPU performs a store or a
  // conflictig load, or if the cache is idle, or after a miss.
  val p_store_match = r_cpu_req_val && r_req_read && p_store_valid && (r_cpu_req_idx(indexlsb-1,offsetlsb) === p_store_idx(indexlsb-1,offsetlsb))
  val drain_store_val = (p_store_valid && (!io.cpu.req_val || req_store || Reg(tag_miss))) || p_store_match
  data_arb.io.in(2).bits.offset := p_store_idx(offsetmsb,ramindexlsb)
  data_arb.io.in(2).bits.idx := p_store_idx(indexmsb,indexlsb)
  data_arb.io.in(2).bits.rw := Bool(true)
  data_arb.io.in(2).bits.wmask := store_wmask_wide
  data_arb.io.in(2).bits.data := store_data_wide
  data_arb.io.in(2).valid := drain_store_val
  val drain_store_rdy = data_arb.io.in(2).ready
  val drain_store = drain_store_val && drain_store_rdy
  val p_store_notready = p_store_valid && !drain_store
  p_store_valid <== p_store_notready || (tag_hit && r_req_store)

  // tag update after a miss or a store to an exclusive clean line.
  val clear_valid = tag_miss && !r_req_flush && meta.io.resp.valid 
  val set_dirty   = tag_hit && !meta.io.resp.dirty && r_req_write
  meta.io.state_req.valid := clear_valid || set_dirty
  meta.io.state_req.bits.idx := r_cpu_req_idx(indexmsb,indexlsb)
  meta.io.state_req.bits.data.valid := tag_match
  meta.io.state_req.bits.data.dirty := tag_match
  
  // pending store data, also used for AMO RHS
  val storegen = new StoreDataGen
  storegen.io.typ := r_cpu_req_type
  storegen.io.addr := r_cpu_req_idx(offsetlsb-1, 0)
  storegen.io.din  := r_cpu_req_data
  when (tag_hit && r_req_store && !p_store_notready) {
    p_store_idx   <== r_cpu_req_idx
    p_store_type  <== r_cpu_req_type
    p_store_data  <== storegen.io.dout
  }

  // miss handling
  val mshr = new MSHRFile
  val replayer = new ReplayUnit
  mshr.io.req_val := tag_miss && !r_req_flush && !nack_mshr
  mshr.io.req_ppn := io.cpu.req_ppn
  mshr.io.req_idx := r_cpu_req_idx(indexmsb,indexlsb)
  mshr.io.req_data := p_store_data
  mshr.io.req_tag := r_cpu_req_tag
  mshr.io.req_offset := r_cpu_req_idx(offsetmsb,0)
  mshr.io.req_cmd := r_cpu_req_cmd
  mshr.io.req_type := r_cpu_req_type
  mshr.io.req_sdq_id := replayer.io.sdq_id
  mshr.io.mem_resp_val := io.mem.resp_val
  mshr.io.mem_resp_tag := io.mem.resp_tag
  mshr.io.mem_req <> mem_arb.io.in(1)
  mshr.io.meta_req <> meta_arb.io.in(1)
  mshr.io.replay <> replayer.io.replay
  replayer.io.sdq_enq.valid := tag_miss && r_req_write && !nack_sdq
  replayer.io.sdq_enq.bits := storegen.io.dout
  data_arb.io.in(0).bits.idx := mshr.io.mem_resp_idx

  // replays
  val replay = replayer.io.data_req.bits
  data_arb.io.in(3).bits.offset := replay.offset(offsetmsb,ramindexlsb)
  data_arb.io.in(3).bits.idx := replay.idx
  data_arb.io.in(3).bits.rw := replay.cmd === M_XWR
  data_arb.io.in(3).bits.wmask := store_wmask_wide
  data_arb.io.in(3).bits.data := store_data_wide
  data_arb.io.in(3).valid := replayer.io.data_req.valid
  replayer.io.data_req.ready := data_arb.io.in(3).ready

  // store write mask generation.
  // assumes pending stores are higher-priority than store replays.
  val maskgen = new StoreMaskGen
  val store_offset = Mux(drain_store_val, p_store_idx(offsetmsb,0), replay.offset)
  maskgen.io.typ := Mux(drain_store_val, p_store_type, replay.typ)
  maskgen.io.addr := store_offset(offsetlsb-1,0)
  store_wmask_wide <== maskgen.io.wmask << Cat(store_offset(ramindexlsb-1,offsetlsb), Bits(0, log2up(CPU_DATA_BITS/8))).toUFix
  val store_data = Mux(drain_store_val, p_store_data, replay.data)
  store_data_wide <== Fill(MEM_DATA_BITS/CPU_DATA_BITS, store_data)

  // load data subword mux/sign extension.
  // assumes load replays are higher-priority than load hits.
  // subword loads are delayed by one cycle.
  val loadgen = new LoadDataGen
  val loadgen_use_replay = Reg(replayer.io.data_req.valid)
  loadgen.io.typ := Mux(loadgen_use_replay, Reg(replay.typ), r_cpu_req_type)
  loadgen.io.addr := Mux(loadgen_use_replay, Reg(replay.offset), r_cpu_req_idx)(offsetlsb-1,0)
  loadgen.io.din := Slice(MEM_DATA_BITS/CPU_DATA_BITS, data.io.resp, r_cpu_req_idx(ramindexlsb-1,offsetlsb).toUFix)

  early_nack <== early_tag_nack || early_load_nack

  val nack_miss_wb = meta.io.resp.dirty && !wb_rdy
  val nack_miss_mshr = !mshr.io.req_rdy
  val nack_miss_sdq = r_req_write && !replayer.io.sdq_enq.ready

  nack_wb <== nack_miss_mshr || nack_miss_sdq || p_store_notready || p_store_match
  nack_mshr <== nack_miss_wb || nack_miss_sdq || p_store_notready || p_store_match
  nack_sdq <== nack_miss_wb || nack_miss_mshr || p_store_notready || p_store_match

  val nack_for_flush = r_req_flush && !flusher.io.req.ready
  val nack = p_store_match || r_req_store && p_store_notready || early_nack ||
             tag_miss && !r_req_flush && (nack_miss_wb || nack_miss_mshr || nack_miss_sdq || p_store_notready)

  // report that cache is always ready.  we nack instead.
  io.cpu.req_rdy   := Bool(true)
  io.cpu.resp_nack := r_cpu_req_val_ && !io.cpu.req_kill && nack
  io.cpu.resp_val  := (tag_hit && !nack && r_req_read) || flusher.io.resp.valid || replayer.io.cpu_resp_val
  io.cpu.resp_miss := tag_miss && !nack && r_req_read
  io.cpu.resp_tag  := Mux(replayer.io.cpu_resp_val, replayer.io.cpu_resp_tag, Mux(flusher.io.resp.valid, flusher.io.resp.bits, r_cpu_req_tag))
  io.cpu.resp_data := loadgen.io.dout
  io.cpu.resp_data_subword := loadgen.io.dout_subword
                      
  val misaligned =
    (((r_cpu_req_type === MT_H) || (r_cpu_req_type === MT_HU)) && (r_cpu_req_idx(0) != Bits(0))) ||
    (((r_cpu_req_type === MT_W) || (r_cpu_req_type === MT_WU)) && (r_cpu_req_idx(1,0) != Bits(0))) ||
    ((r_cpu_req_type === MT_D) && (r_cpu_req_idx(2,0) != Bits(0)));
    
  io.cpu.xcpt_ma_ld := r_cpu_req_val_ && !io.cpu.req_kill && r_req_read && misaligned
  io.cpu.xcpt_ma_st := r_cpu_req_val_ && !io.cpu.req_kill && r_req_write && misaligned

  mem_arb.io.out.ready := io.mem.req_rdy
  io.mem.req_val   := mem_arb.io.out.valid
  io.mem.req_rw    := mem_arb.io.out.bits.rw
  io.mem.req_wdata := wb.io.mem_req_data
  io.mem.req_tag   := mem_arb.io.out.bits.tag.toUFix
  io.mem.req_addr  := mem_arb.io.out.bits.addr
}
 
}
