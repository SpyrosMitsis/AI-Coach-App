// Agent-memory invariants: document assembly, seeding, and pure thread compression.
// Run: `deno test supabase/functions/_shared/agent_memory_test.ts`
import { assert, assertEquals } from "jsr:@std/assert@1";
import { type ChatMessage } from "./llm.ts";
import {
  type AgentMemory,
  compressThread,
  DEFAULT_SOUL,
  memoryDocsBlock,
  memoryFromProfile,
} from "./agent_memory.ts";

const mem = (over: Partial<AgentMemory> = {}): AgentMemory => ({
  user: "",
  memory: "",
  soul: "",
  soulUpdatedAt: null,
  ...over,
});

Deno.test("memoryDocsBlock labels all three docs and omits empties", () => {
  const block = memoryDocsBlock(mem({
    user: "- no overhead press (shoulder)",
    memory: "Responds well to threshold work.",
    soul: "# Coach soul\nI'm warm and direct.",
  }));
  assert(block.includes("COACH IDENTITY & RELATIONSHIP"));
  assert(block.includes("ATHLETE CONSTRAINTS & PREFERENCES"));
  assert(block.includes("ATHLETE MEMORY"));
  assert(block.includes("no overhead press"));

  // Only memory present → only that section appears.
  const onlyMem = memoryDocsBlock(mem({ memory: "x" }));
  assert(onlyMem.includes("ATHLETE MEMORY"));
  assert(!onlyMem.includes("COACH IDENTITY"));

  // All empty → empty string.
  assertEquals(memoryDocsBlock(mem()), "");
});

Deno.test("memoryFromProfile seeds soul from DEFAULT_SOUL when unset", () => {
  const seeded = memoryFromProfile({ coach_knowledge: null, training_memory: null, coach_soul: null });
  assertEquals(seeded.soul, DEFAULT_SOUL);

  const custom = memoryFromProfile({ coach_soul: "# Mine\nhi", coach_soul_updated_at: "2026-06-20T00:00:00Z" });
  assertEquals(custom.soul, "# Mine\nhi");
  assertEquals(custom.soulUpdatedAt, "2026-06-20T00:00:00Z");

  assertEquals(memoryFromProfile(null).soul, DEFAULT_SOUL);
});

const turns = (n: number): ChatMessage[] =>
  Array.from({ length: n }, (_, i) => ({
    role: i % 2 === 0 ? "user" : "assistant",
    content: `m${i}`,
  } as ChatMessage));

Deno.test("compressThread keeps everything for short threads", () => {
  const t = turns(5);
  const { kept, dropped } = compressThread(t);
  assertEquals(kept.length, 5);
  assertEquals(dropped.length, 0);
});

Deno.test("compressThread keeps anchor + recent window, drops the middle", () => {
  const t = turns(30);
  const { kept, dropped } = compressThread(t, 24, 1_000_000);
  assertEquals(kept.length, 24); // anchor + 23 recent
  assertEquals(kept[0].content, "m0"); // anchor preserved
  assertEquals(kept[kept.length - 1].content, "m29"); // newest preserved
  assertEquals(dropped.length, 6); // 30 - 24
  // Dropped are the contiguous middle, in order.
  assertEquals(dropped.map((m) => m.content), ["m1", "m2", "m3", "m4", "m5", "m6"]);
});

Deno.test("compressThread honors the char budget by dropping oldest after anchor", () => {
  // 6 turns, each 100 chars; budget only fits a few.
  const t: ChatMessage[] = Array.from({ length: 6 }, (_, i) => ({
    role: i % 2 === 0 ? "user" : "assistant",
    content: "x".repeat(100),
  } as ChatMessage));
  const { kept, dropped } = compressThread(t, 24, 250);
  // Anchor always kept; total kept content must be within budget.
  assertEquals(kept[0], t[0]);
  const total = kept.reduce((s, m) => s + m.content.length, 0);
  assert(total <= 250);
  assert(dropped.length > 0);
  // Newest is always retained.
  assertEquals(kept[kept.length - 1], t[5]);
});
