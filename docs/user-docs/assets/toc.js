/* Enhance the rendered headings; without JavaScript the guide keeps its full-width two-column layout. */
(() => {
  const disclosureAt = (details, query) => {
    if (!details) return;
    const media = window.matchMedia(query);
    const update = () => { details.open = media.matches; };
    update();
    media.addEventListener('change', update);
  };
  disclosureAt(document.querySelector('.site-navigation'), '(min-width: 761px)');

  const article = document.querySelector('.document-body');
  const toc = document.querySelector('[data-page-toc]');
  const body = document.querySelector('.site-body');
  if (!article || !toc || !body) return;

  const headings = article.querySelectorAll('h2[id], h3[id]');
  if (!headings.length) return;

  const list = toc.querySelector('ol');
  const links = new Map();
  let section = null;
  let subsections = null;
  headings.forEach(heading => {
    const item = document.createElement('li');
    const link = document.createElement('a');
    link.href = '#' + encodeURIComponent(heading.id);
    link.textContent = heading.textContent.trim();
    item.append(link);
    links.set(heading.id, link);
    if (heading.tagName === 'H3' && section) {
      if (!subsections) {
        subsections = document.createElement('ol');
        section.append(subsections);
      }
      subsections.append(item);
    } else {
      list.append(item);
      section = heading.tagName === 'H2' ? item : null;
      subsections = null;
    }
  });

  const updateCurrent = () => {
    let id;
    try { id = decodeURIComponent(window.location.hash.slice(1)); }
    catch { id = ''; }
    links.forEach((link, target) => {
      if (target === id) link.setAttribute('aria-current', 'location');
      else link.removeAttribute('aria-current');
    });
  };
  window.addEventListener('hashchange', updateCurrent);
  updateCurrent();
  disclosureAt(toc.querySelector('details'), '(min-width: 1200px)');
  toc.hidden = false;
  body.classList.add('has-page-toc');
})();
