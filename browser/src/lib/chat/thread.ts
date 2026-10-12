import { getStorage, setStorage } from "@/lib/storage";

// One thread per browser install, generated once and kept — mirrors
// Android's getOrCreateAgentChatThreadId(). The server holds the history.
export async function getOrCreateThreadId(): Promise<string> {
  const { chat_thread_id } = await getStorage("chat_thread_id");
  if (chat_thread_id) return chat_thread_id;
  const fresh = crypto.randomUUID();
  await setStorage({ chat_thread_id: fresh });
  return fresh;
}
