/** 工单分享文案：`<平台地址>/workitems/<id> 《<标题>》`，粘到 IM 里链接和标题都还在。 */
export function buildWorkitemShareText(
  baseUrl: string,
  workitemId: number | string,
  title: string,
): string {
  return `${baseUrl}/workitems/${workitemId} 《${title}》`;
}
