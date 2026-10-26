#!/usr/bin/env python3
"""Replay incremental, attributed content additions from the frozen v209 source.

Keep learner identifiers, levels and existing nonempty IPA. Missing relationships
are not an obligation to invent synonyms, derivatives or inflections.
"""
import copy, gzip, hashlib, json, re
from collections import Counter, defaultdict
from pathlib import Path
from apply_public_content_v17 import apply_review as public_review
from apply_open_dictionary_v17 import apply_review as open_review, relations_review
from apply_bilingual_corpus_v17 import apply_review as corpus_review

ROOT=Path(__file__).resolve().parents[1]
ASSETS=ROOT/'app/src/main/assets'
TOOLS=ROOT/'tools'
OUT=ROOT/'dist/validation-v2.0.10'
def read(path): return json.loads(path.read_text())
def save(path,value): path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n')
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()

MEANINGS={
 'fillet':('n. / vt.','n. 鱼片；肉片；去骨的鱼肉或肉块；带子；头带\nvt. 把鱼或肉去骨切片；用带缚'),
 'funded':('adj. / v.','adj. 得到资金支持的；有资金保障的\nv. 为……提供资金（fund 的过去式和过去分词）'),
 'syndicated':('adj. / v.','adj. （文章、节目）由多家媒体联合刊载或播出的；联合供稿的\nv. 联合供稿或发行；组成联合组织（syndicate 的过去式和过去分词）'),
 'dick':('n.','n. （粗俗）阴茎；（粗俗、贬义）讨厌的人，混蛋；（俚语）侦探'),
 'genus':('n.','n. （生物分类）属；种类'),
 'gaping':('adj. / v.','adj. 张开的；敞开的；裂开的；巨大的\nv. 张大嘴；目瞪口呆地看（gape 的现在分词）'),
 'topping':('n. / adj. / v.','n. （食品的）顶层配料，浇头\nadj. （旧时英式口语）极好的\nv. 在……上面添加；超过；修剪顶部（top 的现在分词）'),
 'coding':('n. / v.','n. 编码；编程\nv. 编码；编写程序（code 的现在分词）'),
 'looming':('adj. / v.','adj. 逼近的；隐约显现的；赫然耸现的\nv. 隐约出现；逼近（loom 的现在分词）'),
 'taped':('adj. / v.','adj. 录制的；用胶带固定的\nv. 录制；用胶带固定（tape 的过去式和过去分词）'),
 'bagel':('n.','n. 贝果，百吉饼（一种通常先水煮再烘烤的面包圈）；（体育俚语）零分'),
 'lore':('n.','n. （某主题或地区的）传统知识；传说'),
 'kayak':('n. / v.','n. 皮划艇\nv. 划皮划艇'),
 'ministry':('n.','n. （政府的）部；内阁；牧师职务；神职工作'),
 'texture':('n. / v.','n. 质地；纹理；结构；（音乐）织体\nv. 使具有某种质地或纹理'),
 'forestry':('n.','n. 林业；林学'),
 'coronary':('adj. / n.','adj. 冠状的；冠状动脉的\nn. （口语）冠状动脉血栓或心脏病发作'),
 'prosthesis':('n.','n. 假体；假肢；人工替代器官；（语言学）词首增音'),
 'sequential':('adj.','adj. 按顺序的；连续的；相继的'),
 'exec':('n.','n. （口语）主管，高级管理人员（executive 的缩写）；（海军）副舰长'),
 'bisexual':('adj. / n.','adj. 双性恋的；（生物）雌雄同体的\nn. 双性恋者'),
 'fungal':('adj.','adj. 真菌的；由真菌引起的'),
 'trillion':('num. / n.','num. / n. 一万亿（10¹²，现代英语通用）；（旧时英式长数制）百万的三次方（10¹⁸）'),
 'leek':('n.','n. 韭葱'),
 'cornmeal':('n.','n. 玉米粉；玉米面'),
 'scallions':('n.','n. 青葱；小葱（scallion 的复数）'),
 'finder':('n.','n. 发现者；查找器；寻星镜'),
 'attracting':('v.','v. 吸引；引起（attract 的现在分词）'),
 'nah':('int.','int. （非正式）不；不要；不了'),
 'amish':('n. / adj.','n. 阿米什派；阿米什派信徒\nadj. 阿米什派的'),
 'underworld':('n.','n. 黑社会；犯罪世界；阴间'),
 'respite':('n. / vt.','n. 短暂的休息；喘息；暂缓；缓期执行\nvt. 使暂缓'),
 'grinder':('n.','n. 研磨机；磨碎器；磨工'),
 'mounting':('adj. / n. / v.','adj. 不断增加的；逐渐加剧的\nn. 安装；底座；衬托纸\nv. 增加；登上；安装（mount 的现在分词）'),
 'stoke':('v.','v. 给炉火添燃料；拨旺；煽动；激起'),
 'custard':('n.','n. 蛋奶酱；蛋奶冻；蛋奶糕'),
 'configure':('vt.','vt. 配置；设置；使成形'),
 'hooker':('n.','n. （橄榄球）钩球队员；（非正式）妓女；用钩子的人；小渔船'),
 'pulitzer':('n.','n. 普利策（姓氏）；普利策奖（Pulitzer Prize 的常用简称）'),
 'silicone':('n.','n. 硅酮；有机硅聚合物；硅胶（材料俗称）'),
 'limbo':('n.','n. 悬而未决的状态；被遗忘的境地；地狱边缘'),
 'unregulated':('adj.','adj. 未受监管的；未经调节的'),
 'buggy':('adj. / n.','adj. （软件等）漏洞多的；多虫的\nn. 轻便马车；（英式）婴儿车'),
 'sioux':('n. / adj.','n. 苏族人；苏族语言\nadj. 苏族的；苏族语言的'),
 'backside':('n.','n. （非正式）屁股；背面；后方'),
 'homemaker':('n.','n. 料理家务者；家庭主妇或家庭主夫'),
 'critter':('n.','n. （非正式）小动物；生物'),
 'stave':('n. / v.','n. （桶等的）板条；棍棒；五线谱；诗节\nv. 击破；打洞；（stave off）暂时挡住，避开'),
 'inlet':('n.','n. 小海湾；水湾；入口；进气口或进水口'),
 'restoring':('v. / adj.','v. 修复；恢复；归还（restore 的现在分词）\nadj. 恢复性的'),
 'andean':('adj.','adj. 安第斯山脉的；安第斯地区的'),
 'cajun':('n. / adj.','n. 卡津人（美国路易斯安那州阿卡迪亚移民的后裔）\nadj. 卡津人的；卡津文化的'),
 'norman':('n. / adj.','n. 诺曼人；诺曼语\nadj. 诺曼人的；诺曼式的；诺曼底的'),
 'lieu':('n.','n. 位置（主要用于 in lieu of：代替；in lieu：作为替代）'),
 'frontline':('n. / adj.','n. 前线；第一线\nadj. 一线的；前沿的'),
 'resurface':('v.','v. 重新铺设……的表面；重新露面；重新浮出水面'),
 'glistening':('adj. / v.','adj. 闪亮的；湿润发光的\nv. 闪光；闪耀（glisten 的现在分词）'),
 'headdress':('n.','n. 头饰；饰头巾'),
 'featured':('adj. / v.','adj. 重点介绍的；作为特色的\nv. 以……为特色；由……主演（feature 的过去式和过去分词）'),
 'fudge':('n. / v.','n. 乳脂软糖；含糊其辞的说法；胡说\nv. 回避；含糊处理；捏造'),
 'decaying':('adj. / v.','adj. 腐烂的；衰败的\nv. 腐烂；衰退（decay 的现在分词）'),
 'pullout':('n. / adj.','n. 撤出；撤军；可抽出的插页\nadj. 可抽出的'),
 'kenyan':('n. / adj.','n. 肯尼亚人\nadj. 肯尼亚的；肯尼亚人的'),
 'walleye':('n.','n. 大眼梭鲈；（旧医学用语）外斜视，角膜白斑'),
 'cambodian':('n. / adj.','n. 柬埔寨人；高棉语\nadj. 柬埔寨的；柬埔寨人的'),
 'lovemaking':('n.','n. 性爱；（旧用法）求爱，调情'),
 'customized':('adj. / v.','adj. 定制的；按用户需求修改的\nv. 定制；按需要修改（customize 的过去式和过去分词）'),
 'underclass':('n.','n. 社会底层弱势群体'),
 'coed':('adj. / n.','adj. 男女同校的；男女混合的\nn. （旧时美式用法）女大学生'),
 'corny':('adj.','adj. （非正式）老套的；陈腐的；煽情而俗气的'),
 'roach':('n.','n. （非正式）蟑螂；斜齿鳊（一种淡水鱼）'),
 'bluegrass':('n.','n. 蓝草音乐（一种美国民间音乐）；早熟禾'),
 'welding':('n. / v.','n. 焊接\nv. 焊接；使紧密结合（weld 的现在分词）'),
 'melanoma':('n.','n. 黑色素瘤'),
 'aerosol':('n. / adj.','n. 气溶胶；气雾剂；喷雾罐\nadj. 喷雾式的'),
 'vigilante':('n.','n. 自发维持治安者；自行惩治他人者'),
 'purveyor':('n.','n. 供应商；提供者；传播者'),
 'inexorably':('adv.','adv. 不可阻挡地；不可改变地；无情地'),
 'broiler':('n.','n. （美式）烤箱上火烤架；烤炉；肉用仔鸡'),
 'dame':('n.','n. 女爵士（英国荣誉称号）；夫人；（旧时非正式）女人'),
 'jettison':('vt. / n.','vt. 投弃（货物）；抛弃；放弃\nn. 投弃；投弃的货物'),
 'beeper':('n.','n. 寻呼机；蜂鸣器'),
 'sampler':('n.','n. 多种样品的组合；取样器；采样者；刺绣样本'),
 'swirling':('adj. / v.','adj. 旋转的；打旋的\nv. 旋转；打旋（swirl 的现在分词）'),
 'steeply':('adv.','adv. 陡峭地；急剧地'),
 'accredit':('vt.','vt. 认证；认可……的资质；委派；把……归于'),
 'blanch':('v.','v. 焯（蔬菜等）；使变白；（脸色）变白'),
 'leasing':('n. / v.','n. 租赁\nv. 出租；租用（lease 的现在分词）'),
}

def main():
 before=json.loads(gzip.decompress((TOOLS/'data/v209-vocabulary.json.gz').read_bytes()))
 words=copy.deepcopy(before);old={w['id']:w for w in before};index={w['id']:w for w in words}
 inputs=[];archive=[];counts=Counter()
 example_names=['authored_examples_v16_grade6.json','authored_content_v17_a.json','authored_examples_v17_advanced_a.json','authored_examples_v17_grade6_remaining.json','authored_examples_v17_grade7_a.json','authored_examples_v17_grade7_tail.json','authored_examples_v17_grade7_remaining.json']
 example_names += sorted(p.name for p in TOOLS.glob('authored_examples_v17_grade*.json') if re.fullmatch(r'authored_examples_v17_grade(?:8|9|10)_[a-z]+\.json',p.name))
 for name in example_names:
  path=TOOLS/name
  if not path.exists():continue
  inputs.append(name)
  for head,patch in read(path)['words'].items():
   w=index[head]
   if not w['examples'] and patch.get('examples'):
    w['examples']=copy.deepcopy(patch['examples'][:2]);counts['authoredExampleWords']+=1
   for field in ['meaningZh','partOfSpeech']:
    if field in patch:w[field]=patch[field]
   for c in patch.get('collocations',[]):
    if len(w['collocations'])<4 and not any(x['en']==c['en'] for x in w['collocations']):w['collocations'].append(copy.deepcopy(c))
 # A forced adverb phrase was not a useful collocation; retain its real example.
 if not old['lastly']['collocations']:index['lastly']['collocations']=[]
 for head,(pos,meaning) in MEANINGS.items():
  index[head].update(partOfSpeech=pos,meaningZh=meaning)
 for pair in read(TOOLS/'authored_relations_v17.json')['pairs']:
  for head,target in [(pair['a'],pair['b']),(pair['b'],pair['a'])]:
   assert head in index and target in index
   values=index[head]['confusables'];existing=next((x for x in values if x['word']==target),None)
   item={'word':target,'meaningZh':index[target]['meaningZh'],'note':pair['note']}
   if existing:existing.update(item)
   else:values.append(item)
 inputs.extend(['authored_relations_v17.json','authored_meaning_references_v17.json'])
 translations={}
 for name in ['authored_translations_v16_grade45_b.json','authored_translations_v16_grade6_b.json','authored_translations_v17_grade45_c.json','authored_translations_v17_a.json','authored_translations_v17_core_remaining.json']:
  path=TOOLS/name
  if path.exists():inputs.append(name);translations.update(read(path)['translations'])
 for w in words:
  for e in w['examples']:
   if not e['zh'] and translations.get(e['en']):
    e.update(zh=translations[e['en']],translationCredit='中文译文：项目原创 v17（CC0）',translationLicense='CC0 1.0')
 replacements=[]
 for path in sorted(TOOLS.glob('authored_translations_*_replacements.json')):
  inputs.append(path.name);replacements.extend(read(path)['replacements'])
 for head,needle,en,zh,reason in [
  ('unhealthy','Beer is not really','The article discussed unhealthy eating habits.','这篇文章讨论了不健康的饮食习惯。','以中性的词义语境替换缺少条件的健康断言。'),
  ('trillion','in England','The report lists a total cost of one trillion dollars.','报告列出的总成本为一万亿美元。','旧例句采用未说明时代的长数制；改为现代英语万亿义。'),
  ('antimatter','Antimatter is highly unstable.','The experiment studied the behavior of antimatter.','这项实验研究了反物质的行为。','原句对反物质作无条件的不稳定断言。'),
  ('computerized',"80% of the world's",'She searched the computerized records.','她检索了计算机化的记录。','移除缺少年份依据的信息占比断言。'),
 ]:
  for e in index[head]['examples']:
   if needle in e['en']:replacements.append({'wordId':head,'originalEnglish':e['en'],'reason':reason,'suggestedExample':{'en':en,'zh':zh,'source':'项目原创语境 v17 · CC0','attribution':'项目原创（CC0）','license':'CC0 1.0'}})
 for row in replacements:
  w=index[row['wordId']]
  for n,e in enumerate(w['examples']):
   if e['en']==row['originalEnglish']:
    archive.append({'wordId':w['id'],'original':copy.deepcopy(e),'reason':row['reason'],'replacement':row['suggestedExample']})
    w['examples'][n]=copy.deepcopy(row['suggestedExample'])
 public=read(TOOLS/'public_content_v17.json');words,readings,public_counts=public_review(words,public)
 words,open_readings,open_counts,open_archive=open_review(words);archive.extend(open_archive)
 words,corpus_counts,corpus_archive=corpus_review(words);archive.extend(corpus_archive)
 words,relation_credits=relations_review(words)
 index={w['id']:w for w in words}
 for head,accents in open_readings.items():
  for accent,values in accents.items():
   for value in values:
    if value not in readings[head][accent]:readings[head][accent].append(value)
 inputs.extend(['public_content_v17.json','public_wordforms_v17.json','public_bilingual_corpus_v17.json','data/open-dictionary-v2.0.jsonl.gz'])
 forms=read(TOOLS/'public_wordforms_v17.json');formcredits=[]
 # Avoid marginal countable senses and uncommon textbook comparative forms.
 accepted={'able','subtle','coy','eerie','falsetto','gnarly','handball','nosy','stark','tacky'}
 for head,items in forms['words'].items():
  if head in accepted and not index[head]['wordForms']:
   index[head]['wordForms']=items;formcredits.extend(c for c in forms['credits'] if c['word']==head)
 donors=defaultdict(list)
 for w in words:
  for e in w['examples']:
   if e['zh']:donors[e['en']].append((w['id'],e))
 for w in words:
  for e in w['examples']:
   ds=donors.get(e['en'],[])
   if not e['zh'] and ds and len({d['zh'] for _,d in ds})==1:
    head,d=ds[0];e['zh']=d['zh'];e['translationReuseWordId']=head
    for key in ['translationCredit','translationLicense','translationSourceUrl']:
     if d.get(key):e[key]=d[key]
  for field in ['derivatives','confusables','synonyms']:
   for item in w[field]:
    head=item['word']
    if head in old and item['meaningZh']==old[head]['meaningZh']:item['meaningZh']=index[head]['meaningZh']
 related=json.loads(gzip.decompress((TOOLS/'data/v209-related-pronunciations.json.gz').read_bytes()))
 ipacredits={}
 for head,row in related.items():
  for accent,field in [('us','pronunciationUs'),('uk','pronunciationUk')]:
   if not row.get(accent):
    own=index.get(head,{}).get(field,'');new=own or ', '.join(readings[head][accent][:4])
    if new:
     row[accent]=new;counts['relatedIPA_'+accent]+=1
     if not own:ipacredits.setdefault(head,{})[accent]=new
 for w in words:
  for accent,field in [('us','pronunciationUs'),('uk','pronunciationUk')]:
   if w[field] and not old[w['id']][field]:ipacredits.setdefault(w['word'],{})[accent]=w[field]
 from build_vocabulary import validate
 validate(words)
 assert [w['id'] for w in words]==[w['id'] for w in before]
 for w in words:
  for field in ['word','rank','difficulty']:assert w[field]==old[w['id']][field],(w['id'],field)
  for field in ['pronunciationUs','pronunciationUk']:
   if old[w['id']][field]:assert w[field]==old[w['id']][field]
  assert all(e['zh'] for e in w['examples']) if not old[w['id']]['examples'] else True
 ASSETS.joinpath('vocabulary.json').write_text(json.dumps(words,ensure_ascii=False,separators=(',',':'))+'\n')
 save(ASSETS/'related_pronunciations.json',related);save(TOOLS/'related_pronunciations_v17.json',related)
 save(ASSETS/'public_relations_v17_applied.json',relation_credits)
 save(ASSETS/'meaning_references_v17.json',read(TOOLS/'authored_meaning_references_v17.json'))
 save(ASSETS/'public_content_v17_applied.json',{'snapshotSHA256':public['snapshotSHA256'],'source':'https://kaikki.org/dictionary/downloads/zh/zh-extract.jsonl.gz','license':'CC BY-SA 4.0','words':ipacredits,'wordForms':formcredits,'wordSourceUrlTemplate':'https://zh.wiktionary.org/wiki/{word}','additionalSource':{'url':'https://github.com/ahpxex/open-dictionary/releases/tag/v2.0','sha256':'95026a4b78aa83f1f32481e8ff30ea3f7d942b648b3d6e66ef64ca3a194cb66e','disclosure':'Wiktionary-grounded model-expanded learner dictionary','readings':{h:r for h,r in open_readings.items() if h in ipacredits}}})
 report={'version':'2.0.10','wordsChanged':sum(w!=old[w['id']] for w in words),'newExampleWords':sum(not old[w['id']]['examples'] and bool(w['examples']) for w in words),'newTranslatedItems':sum(not e['zh'] for w in before for e in w['examples'])-sum(not e['zh'] for w in words for e in w['examples']),'missingExampleWords':sum(not w['examples'] for w in words),'untranslatedExampleItems':sum(not e['zh'] for w in words for e in w['examples']),'meaningCorrections':sum(w['meaningZh']!=old[w['id']]['meaningZh'] for w in words),'newCollocationItems':sum(len(w['collocations'])-len(old[w['id']]['collocations']) for w in words),'newWordFormItems':sum(len(w['wordForms'])-len(old[w['id']]['wordForms']) for w in words),'replacedExamples':len(archive),'counts':dict(counts),'public':public_counts,'openDictionary':open_counts,'incidentalCorpus':corpus_counts,'newDerivativeItems':sum(len(w['derivatives'])-len(old[w['id']]['derivatives']) for w in words),'newSynonymItems':sum(len(w['synonyms'])-len(old[w['id']]['synonyms']) for w in words),'inputs':{name:sha(TOOLS/name) for name in inputs},'remainingByDifficulty':{str(d):sum(not w['examples'] for w in words if w['difficulty']==d) for d in range(1,11)}}
 m=read(TOOLS/'data/v209-dictionary-sources.json');m.update(contentVersion=20,vocabularySha256=sha(ASSETS/'vocabulary.json'))
 m['enhancedCountsByDifficulty']={str(d):sum(bool(w['examples']) for w in words if w['difficulty']==d) for d in range(1,11)}
 m['enhancedCounts']={f:sum(bool(w[f]) for w in words) for f in ['examples','collocations','derivatives','confusables','wordForms']}
 for field,key in [('examples','exampleItemCount'),('collocations','collocationItemCount'),('derivatives','derivativeItemCount'),('wordForms','wordFormItemCount')]:m[key]=sum(len(w[field]) for w in words)
 m['bilingualExampleWordCount']=sum(any(e['zh'] for e in w['examples']) for w in words)
 m['englishOnlyExampleWordCount']=sum(bool(w['examples']) and not any(e['zh'] for e in w['examples']) for w in words)
 m['completeCoreWordCount']=sum(all(w[f] for f in ['examples','collocations','derivatives','confusables']) for w in words)
 m['britishPronunciationWordCount']=sum(bool(w['pronunciationUk']) for w in words);m['missingBritishPronunciationWordCount']=sum(not w['pronunciationUk'] for w in words)
 m['relatedPronunciations']['sha256']=sha(ASSETS/'related_pronunciations.json')
 m['relatedPronunciations']['source']='既有读音及 v17 中文维基词典明确标注口音的公开读音；不推测缺失口音。'
 m['originalInputSha256'].update(report['inputs']);m['androidV17ContentReview']=report
 save(ASSETS/'dictionary_sources.json',m)
 OUT.mkdir(parents=True,exist_ok=True);save(OUT/'content-changes.json',report);save(OUT/'removed-examples.json',archive)
 notice=ASSETS/'dictionary_licenses.txt';text=notice.read_text();marker='\n\n===== PROJECT CONTENT V17 =====\n'
 notice.write_text(text.partition(marker)[0]+marker+'Project-authored examples, Chinese translations, collocations and gloss revisions are CC0 1.0. Existing English sentences retain their original credits. New Wiktionary bilingual examples, explicitly labelled accent readings and selected inflections are CC BY-SA 4.0 (https://creativecommons.org/licenses/by-sa/4.0/); per-word sources and the downloaded snapshot SHA256 are retained in the examples and public_content_v17_applied.json. Additional bilingual learning contexts and explicitly labelled IPA are selected from ahpxex/open-dictionary v2.0, a Wiktionary-grounded, model-expanded dataset (CC BY-SA 4.0): https://github.com/ahpxex/open-dictionary/releases/tag/v2.0 ; license: https://github.com/ahpxex/open-dictionary/blob/main/LICENSE-DATA.md . Adaptations include sense/POS matching, selection and integration; original untranslated examples that were replaced are archived in dist/validation-v2.0.10/removed-examples.json.\n')
 print(json.dumps(report,ensure_ascii=False,indent=2))
if __name__=='__main__': main()
